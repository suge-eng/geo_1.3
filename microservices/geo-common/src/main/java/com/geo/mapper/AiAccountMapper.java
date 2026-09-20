package com.geo.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.geo.entity.AiAccount;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 【AI平台账号池Mapper】
 *
 * 设计思路（账号池调度算法核心）：
 * 1. 为什么需要账号池？
 *    - AI平台（豆包/Kimi等）对单个账号有限制：每日提问数限制、频率限制、验证码风险。
 *    - 所以我们为每个平台准备多个账号（比如20个豆包账号），轮流使用，分摊压力。
 *
 * 2. 核心调度策略：
 *    - daily_used：当日已使用次数。每次用incrementDailyUsed+1，到了阈值就暂时不用。
 *    - consecutive_failures：连续失败次数。连续失败太多（如5次）说明账号可能被封了，进入冷却。
 *    - status + cooldown_until：账号状态和冷却结束时间。被冷却的账号selectBestAccount时不会被挑中。
 *
 * 3. 定时任务：
 *    - resetDailyUsed：每天凌晨定时跑，把所有账号的daily_used清零（新的一天开始了）。
 */
@Mapper
public interface AiAccountMapper extends BaseMapper<AiAccount> {

    /** 查询某平台所有"可用"账号（状态为ACTIVE的，用于列表展示） */
    List<AiAccount> selectAvailableAccounts(@Param("platform") String platform);

    /**
     * 智能挑一个"最优账号"（核心逻辑写在SQL里）：
     * 排序优先级：daily_used少 → 最后使用时间早 → 连续失败少
     * 这样实现的效果就是"尽量平均使用每个账号，同时避开坏账号"。
     */
    AiAccount selectBestAccount(@Param("platform") String platform);

    /** 账号被使用一次：当日使用次数+1，更新最后使用时间 */
    @Update("UPDATE ai_account SET daily_used = daily_used + 1, last_request_at = NOW() WHERE id = #{id}")
    int incrementDailyUsed(@Param("id") Long id);

    /** 每日定时重置：把过了重置时间（昨天）的账号日使用量清零 */
    @Update("UPDATE ai_account SET daily_used = 0, daily_reset_at = #{resetTime} WHERE daily_reset_at < #{resetTime}")
    int resetDailyUsed(@Param("resetTime") LocalDateTime resetTime);

    /** 账号使用失败：连续失败次数+1 */
    @Update("UPDATE ai_account SET consecutive_failures = consecutive_failures + 1 WHERE id = #{id}")
    int incrementFailureCount(@Param("id") Long id);

    /** 账号使用成功：清零连续失败次数（账号恢复健康状态） */
    @Update("UPDATE ai_account SET consecutive_failures = 0 WHERE id = #{id}")
    int resetFailureCount(@Param("id") Long id);

    /**
     * 账号"生病"了：设置状态（如COOLDOWN冷却中）+ 冷却到什么时候。
     * 等cooldown_until时间过了之后，定时任务会把状态改回ACTIVE。
     */
    @Update("UPDATE ai_account SET status = #{status}, cooldown_until = #{cooldownUntil} WHERE id = #{id}")
    int updateStatusAndCooldown(@Param("id") Long id, @Param("status") String status, @Param("cooldownUntil") LocalDateTime cooldownUntil);

    // ========== 以下是 worker 账号池调度新增的方法 ==========

    /**
     * 【worker 账号分配】挑一批空闲账号（最多 5 个，LIMIT 5），返回 List 供服务端逐个原子尝试绑定。
     * SQL 条件保证只从 worker_id IS NULL 的账号里选，防止重复分配。
     * 排序：priority DESC + daily_used ASC + RAND()，高优先级/用量少/随机。
     */
    List<AiAccount> selectIdleAccountForWorker(@Param("platform") String platform);

    /**
     * 【原子绑定】把一个空闲账号绑定给指定 worker。
     * 条件：worker_id IS NULL AND id = #{accountId} AND platform = #{platform} AND status = 'ACTIVE'
     * 返回受影响行数，0 表示已被别人抢走或不可用。
     * daily_limit <= 0 表示无限额度。
     */
    @Update("UPDATE ai_account SET worker_id = #{workerId}, batch_count = 0, borrowed_at = NOW(), last_request_at = NOW(), daily_used = daily_used + 1 " +
            "WHERE id = #{accountId} AND platform = #{platform} AND status = 'ACTIVE' AND worker_id IS NULL " +
            "AND (cooldown_until IS NULL OR cooldown_until < NOW()) " +
            "AND (daily_limit IS NULL OR daily_limit <= 0 OR daily_used < daily_limit) " +
            "AND consecutive_failures < max_consecutive_failures")
    int bindWorker(@Param("accountId") Long accountId, @Param("platform") String platform, @Param("workerId") String workerId);

    /**
     * 【原子递增批次计数】worker 处理完一个问题后 batch_count + 1。
     * 当 batch_count 达到 BATCH_SIZE（服务端判断或调用方判断）时 worker 应主动释放。
     * 只有持有该账号的 worker 才能递增（WHERE worker_id = #{workerId}）。
     */
    @Update("UPDATE ai_account SET batch_count = batch_count + 1, daily_used = daily_used + 1, last_request_at = NOW(), consecutive_failures = 0 " +
            "WHERE id = #{accountId} AND worker_id = #{workerId}")
    int incrementBatchAndDailyUsed(@Param("accountId") Long accountId, @Param("workerId") String workerId);

    /**
     * 【释放账号】worker 用完后（或换号时）主动释放，让账号重新变为可分配状态。
     * 只清 worker 绑定 + 批次计数，不改状态、不写冷却时间。
     * 设计思路：这个方法是"通用释放绑定"语义，冷却与否由调用方决定，
     * 所以这里保持最小副作用，避免误伤其它只想释放绑定的场景。
     */
    @Update("UPDATE ai_account SET worker_id = NULL, batch_count = 0 " +
            "WHERE id = #{accountId} AND worker_id = #{workerId}")
    int releaseByWorker(@Param("accountId") Long accountId, @Param("workerId") String workerId);

    /**
     * 【释放账号并写入冷却】（原子操作）worker 用完/换号时，用单条 SQL 同时完成：
     *   1. 清 worker 绑定 + batch_count
     *   2. 置状态 MAINTENANCE + 冷却到 now()+cooldownMinutes 分钟
     * 设计思路：早期实现是"先 releaseByWorker 清绑定、再 updateStatusAndCooldown 补冷却"两段式，
     *   中间会被清扫器(每30秒 reclaimIdleWorkerAccounts)插队造成 worker_id 不匹配，
     *   导致 released=0、冷却被静默跳过。这里合并成一条原子 UPDATE，WHERE 用 id+worker_id 兜底，
     *   要么整条成功、要么整条不生效，从根上消除竞态。
     */
    @Update("UPDATE ai_account SET worker_id = NULL, batch_count = 0, " +
            "status = 'MAINTENANCE', cooldown_until = NOW() + INTERVAL #{cooldownMinutes} MINUTE " +
            "WHERE id = #{accountId} AND worker_id = #{workerId}")
    int releaseAndCooldownAccount(@Param("accountId") Long accountId,
                                  @Param("workerId") String workerId,
                                  @Param("cooldownMinutes") int cooldownMinutes);

    /**
     * 【释放所有被某个 worker 持有的账号】worker 下线/宕机清扫时用。
     */
    @Update("UPDATE ai_account SET worker_id = NULL, batch_count = 0 WHERE worker_id = #{workerId}")
    int releaseAllByWorker(@Param("workerId") String workerId);

    /**
     * 【冷却到期自动恢复】把 cooldown_until < NOW() 的 MAINTENANCE 账号恢复为 ACTIVE。
     * 定时任务每 30 秒调一次。同时清零 consecutive_failures（冷却完说明风险解除了）。
     */
    @Update("UPDATE ai_account SET status = 'ACTIVE', cooldown_until = NULL, consecutive_failures = 0 " +
            "WHERE status = 'MAINTENANCE' AND cooldown_until IS NOT NULL AND cooldown_until < NOW()")
    int recoverExpiredCooldown();

    /**
     * 【日额度耗尽自动恢复】把 daily_limit > 0 但 daily_used >= daily_limit 的 EXHAUSTED 账号恢复为 ACTIVE。
     * 等次日 resetDailyUsed 把 daily_used 清零后自然就满足条件了。
     */
    @Update("UPDATE ai_account SET status = 'ACTIVE' " +
            "WHERE status = 'EXHAUSTED' AND (daily_limit IS NULL OR daily_limit <= 0 OR daily_used < daily_limit)")
    int recoverExhaustedAccounts();

    /**
     * 【worker/手动登录脚本上传 cookie】把 cookie JSON 存到账号记录。
     * 只按 accountId 更新——cookie 属于账号本身，无论账号当前是否被借走、
     * 被哪个 worker 持有，都允许上传最新 cookie（谁登录/刷新谁的 cookie 就最新）。
     */
    @Update("UPDATE ai_account SET cookie = #{cookie} WHERE id = #{accountId}")
    int updateCookie(@Param("accountId") Long accountId,
                     @Param("cookie") String cookie);
}