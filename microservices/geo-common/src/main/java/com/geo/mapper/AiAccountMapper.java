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
}