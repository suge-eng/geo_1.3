package com.geo.service;

import com.geo.common.BusinessException;
import com.geo.common.ResultCode;
import com.geo.entity.AiAccount;
import com.geo.enums.AccountStatus;
import com.geo.mapper.AiAccountMapper;
import com.geo.mapper.TaskResultMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 【RPA 侧账号池调度服务】
 *
 * 设计思路：
 *   这个 Service 和 geo-task-service 里的 AccountPoolService 功能一致，但多了 worker 绑定/释放的逻辑。
 *   原因是：worker 的 HTTP 请求打到 rpa-service，账号池调度是 worker 链路的一部分，
 *   直接在 rpa-service 里操作避免跨微服务调用。
 *
 * ============= Worker 账号分配流程 =============
 * 1. worker 启动 → 调 acquireAccount(platform, workerId) 借一个账号
 *    - 先从 SQL 选出来的前 5 个空闲账号里逐个尝试 bindWorker
 *    - bindWorker 是原子 SQL（WHERE worker_id IS NULL），并发下多 worker 抢也只会一个成功
 *    - 全失败 → 抛 ACCOUNT_EXHAUSTED，worker 等待后重试
 *
 * 2. worker 拿到账号后 → 用这个账号连跑 BATCH_SIZE（如10）个问题
 *    - 每完成一个问题（回调时）调 incrementAfterUse(accountId, workerId, success)
 *    - batch_count 自动 +1，达到 BATCH_SIZE 时 worker 主动释放
 *
 * 3. worker 用完 N 个 → releaseAccount(accountId, workerId)
 *    - 或者账号触发冷却 → markAccountCooldown 设 cooldown_until（默认冷却 10 分钟）
 *    - 之后重新借下一个账号
 *
 * 4. 失败处理：连续失败达到 maxConsecutiveFailures → 自动标记冷却
 *
 * ============= 离线清扫 =============
 *   定时任务每 60 秒扫一次：如果一个账号被 worker 借走超过 30 分钟，
 *   且该 worker 在 task_result 里已经没有任何 RUNNING 单元了，
 *   就释放账号绑定（防止 worker 电脑关机后账号被永久占着）。
 */
@Service
public class AccountPoolService {

    private static final Logger log = LoggerFactory.getLogger(AccountPoolService.class);

    /** 默认冷却时长（分钟）—— 用户需求：每个账号冷却 10 分钟 */
    private static final int DEFAULT_COOLDOWN_MINUTES = 10;

    /** 账号被 worker 持有超过这个时间且该 worker 没 RUNNING 单元 → 释放 */
    private static final long OFFLINE_CLEANUP_MINUTES = 30;

    private final AiAccountMapper aiAccountMapper;
    private final TaskResultMapper taskResultMapper;

    public AccountPoolService(AiAccountMapper aiAccountMapper, TaskResultMapper taskResultMapper) {
        this.aiAccountMapper = aiAccountMapper;
        this.taskResultMapper = taskResultMapper;
    }

    // ==================== 1. 借号 / 还号 ====================

    /**
     * 【借账号给 worker】核心入口。
     * 策略：先拿 SQL 选好的前 5 个空闲账号，逐个尝试原子绑定，谁先绑上用谁。
     * 全失败就抛异常。
     */
    @Transactional
    public AiAccount acquireAccount(String platform, String workerId) {
        // 顺手做日额度重置（如果需要）
        resetDailyCountersIfNeeded();

        // 先查一批候选（最多 5 个，避免全表扫描）
        List<AiAccount> candidates = aiAccountMapper.selectIdleAccountForWorker(platform);
        if (candidates == null || candidates.isEmpty()) {
            log.warn("平台 {} 没有空闲可用账号", platform);
            throw new BusinessException(ResultCode.ACCOUNT_EXHAUSTED, "平台 " + platform + " 暂无可用账号");
        }

        // 逐个尝试原子绑定（乐观锁式抢号）
        for (AiAccount acc : candidates) {
            int bound = aiAccountMapper.bindWorker(acc.getId(), platform, workerId);
            if (bound > 0) {
                log.info("worker[{}] 成功借到账号: accountId={}, accountName={}, platform={}",
                        workerId, acc.getId(), acc.getAccountName(), platform);
                // 重新查出完整字段（SQL 更新了 worker_id/batch_count 等，但当前 acc 对象内存里还没这些值）
                AiAccount fresh = aiAccountMapper.selectById(acc.getId());
                if (fresh == null) {
                    throw new BusinessException(ResultCode.ACCOUNT_EXHAUSTED, "账号绑定成功但查询不到");
                }
                return fresh;
            }
            // 没绑上说明已被其他 worker 抢走或条件不满足，继续试下一个
            log.debug("worker[{}] 尝试 accountId={} 失败（可能已被抢走或不可用），继续试下一个", workerId, acc.getId());
        }

        log.warn("worker[{}] 尝试了全部 {} 个候选账号都绑定失败，可能并发竞争激烈", workerId, candidates.size());
        throw new BusinessException(ResultCode.ACCOUNT_EXHAUSTED, "平台 " + platform + " 账号暂时不可用，稍后重试");
    }

    /**
     * 【释放账号】worker 用完后主动释放，让账号回到空闲池。
     * 只有持有该账号的 worker 才能释放（WHERE worker_id = #{workerId}）。
     *
     * 设计思路：
     *   批次完成后释放账号 → 立即设置 DEFAULT_COOLDOWN_MINUTES（10分钟）冷却，
     *   防止账号刚释放又被同一个/另一个 worker 立刻借回继续用，
     *   让账号有"呼吸时间"，降低被 AI 平台风控/限流的概率。
     *
     *   冷却机制：cooldown_until = NOW() + 10min，状态改为 MAINTENANCE。
     *   定时任务 autoRecoverExpiredCooldown 每 30 秒扫一次，到期自动恢复为 ACTIVE。
     *
     *   注意：这里故意不区分"批次完成"和"换号继续跑"——正常释放一律冷却，
     *   如果未来需要区分（比如换同平台另一个账号不该冷却），可以加 releaseReason 参数。
     */
    @Transactional
    public boolean releaseAccount(Long accountId, String workerId) {
        // 一次原子操作完成「释放绑定 + 置冷却」，防止两步之间的清扫器回收造成冷却被静默跳过
        int released = aiAccountMapper.releaseAndCooldownAccount(accountId, workerId, DEFAULT_COOLDOWN_MINUTES);
        if (released > 0) {
            log.info("worker[{}] 成功释放账号并设置 {} 分钟冷却: accountId={}",
                    workerId, DEFAULT_COOLDOWN_MINUTES, accountId);
            return true;
        }
        log.warn("worker[{}] 释放账号失败（可能已被回收或不属于该 worker）: accountId={}", workerId, accountId);
        return false;
    }

    /**
     * 【释放一个 worker 持有的所有账号】worker 下线/心跳丢失时，清扫器用。
     */
    @Transactional
    public int releaseAllByWorker(String workerId) {
        int released = aiAccountMapper.releaseAllByWorker(workerId);
        if (released > 0) {
            log.warn("清扫器释放了 worker[{}] 的 {} 个账号（worker 可能已离线）", workerId, released);
        }
        return released;
    }

    // ==================== 2. 使用回调（成功/失败 → 更新账号状态） ====================

    /**
     * 【使用后回调】worker 完成一个问题后调用。
     * - success=true：batch_count+1, daily_used+1, 连续失败归零（账号恢复健康）
     * - success=false：batch_count+1, daily_used+1, 连续失败+1；达到阈值自动冷却 DEFAULT_COOLDOWN_MINUTES 分钟
     *
     * 成功路径用单个原子 SQL（incrementBatchAndDailyUsed）保证 batch_count 和 daily_used 不丢更新；
     * 失败路径额外再 incrementFailureCount。虽然不是完美原子，但实际并发下账号是被单个 worker 独占的，
     * 所以这个"先 +1 成功计数再单独处理失败"的两段式不会有冲突。
     */
    @Transactional
    public void incrementAfterUse(Long accountId, String workerId, boolean success) {
        // 第一步：原子 +1 批次计数和日用量（incrementBatchAndDailyUsed 里写了 consecutive_failures=0，
        // 不管成功失败先归零——失败回调下面再单独 +1 回来，这是简化处理，实际因为账号被单个 worker 独占，
        // 不会有并发问题）
        int affected = aiAccountMapper.incrementBatchAndDailyUsed(accountId, workerId);
        if (affected == 0) {
            log.warn("incrementAfterUse 失败：worker[{}] 可能已不再持有 accountId={}，跳过", workerId, accountId);
            return;
        }

        // 第二步：失败时额外 +1 连续失败计数并检查是否需要冷却
        if (!success) {
            AiAccount account = aiAccountMapper.selectById(accountId);
            if (account != null) {
                aiAccountMapper.incrementFailureCount(accountId);
                if (account.getConsecutiveFailures() + 1 >= account.getMaxConsecutiveFailures()) {
                    // 自动冷却 DEFAULT_COOLDOWN_MINUTES 分钟
                    LocalDateTime cooldownUntil = LocalDateTime.now().plusMinutes(DEFAULT_COOLDOWN_MINUTES);
                    aiAccountMapper.updateStatusAndCooldown(accountId, AccountStatus.MAINTENANCE.name(), cooldownUntil);
                    // 同时释放 worker 绑定
                    aiAccountMapper.releaseByWorker(accountId, workerId);
                    log.warn("账号 accountId={} 连续失败达到阈值，自动冷却 {} 分钟并释放 worker[{}]",
                            accountId, DEFAULT_COOLDOWN_MINUTES, workerId);
                }
            }
        }
    }

    /**
     * 【标记冷却】手动触发账号冷却（风控、验证码等场景）。
     * 冷却期间账号不会被分配给任何 worker。同时释放 worker 绑定。
     */
    @Transactional
    public void markAccountCooldown(Long accountId, String workerId, int minutes) {
        LocalDateTime cooldownUntil = LocalDateTime.now().plusMinutes(minutes);
        aiAccountMapper.updateStatusAndCooldown(accountId, AccountStatus.MAINTENANCE.name(), cooldownUntil);
        // 冷却同时释放 worker 绑定
        aiAccountMapper.releaseByWorker(accountId, workerId);
        log.warn("账号 accountId={} 被标记冷却 {} 分钟", accountId, minutes);
    }

    // ==================== 3. 兼容旧调用方 ====================

    /** 【手动成功回调】兼容 task-service 那边的调用 */
    @Transactional
    public void markAccountSuccess(Long accountId) {
        aiAccountMapper.resetFailureCount(accountId);
    }

    /** 【手动失败回调】兼容 task-service 那边的调用 */
    @Transactional
    public void markAccountFailure(Long accountId) {
        AiAccount account = aiAccountMapper.selectById(accountId);
        if (account != null) {
            aiAccountMapper.incrementFailureCount(accountId);
            if (account.getConsecutiveFailures() + 1 >= account.getMaxConsecutiveFailures()) {
                updateAccountStatus(accountId, AccountStatus.MAINTENANCE.name(), LocalDateTime.now().plusHours(1));
            }
        }
    }

    /** 私有工具方法 */
    private void updateAccountStatus(Long accountId, String status, LocalDateTime cooldownUntil) {
        aiAccountMapper.updateStatusAndCooldown(accountId, status, cooldownUntil);
    }

    /**
     * 【日额度重置】每次借账号时顺手检查：过了凌晨就把所有账号的 daily_used 归零。
     * 设计巧妙之处：不用定时任务，借用请求触发即可。
     */
    @Transactional
    public void resetDailyCountersIfNeeded() {
        LocalDateTime midnight = LocalDateTime.now().toLocalDate().atStartOfDay().plusDays(1);
        aiAccountMapper.resetDailyUsed(midnight);
    }

    // ==================== 4. 冷却恢复 + 离线清扫定时任务 ====================

    /**
     * 【冷却到期自动恢复】每 30 秒扫一次：把 MAINTENANCE 状态且 cooldown_until < NOW() 的账号
     * 自动恢复为 ACTIVE，同时清零连续失败计数。
     *
     * 这就是"所有账号都在冷却"情况下的逃生通道：worker 每 15 秒重试借号，
     * 这个定时任务每 30 秒把冷却到期的账号恢复回来，worker 下一次重试就能借到了。
     */
    @Scheduled(fixedDelay = 30000, initialDelay = 15000)
    @Transactional
    public void autoRecoverExpiredCooldown() {
        int recovered = aiAccountMapper.recoverExpiredCooldown();
        if (recovered > 0) {
            log.info("冷却恢复定时任务：已恢复 {} 个冷却到期的账号", recovered);
        }
        // 顺便处理 EXHAUSTED 恢复（主要给设了 daily_limit > 0 的账号用）
        aiAccountMapper.recoverExhaustedAccounts();
    }

    /**
     * 【离线 worker 账号清扫】每 60 秒扫一次：如果账号被借走超过 OFFLINE_CLEANUP_MINUTES 分钟，
     * 且该 worker 在 task_result 里已经没有任何 RUNNING 单元了，
     * 就释放账号绑定。防止 worker 电脑关机后账号被永久占着。
     */
    @Scheduled(fixedDelay = 60000, initialDelay = 30000)
    @Transactional
    public void reclaimIdleWorkerAccounts() {
        // 扫出所有被借走的账号（worker_id 不为空），逐个检查
        List<AiAccount> borrowed = aiAccountMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<AiAccount>()
                        .isNotNull("worker_id")
        );
        if (borrowed == null || borrowed.isEmpty()) {
            return;
        }

        int releasedCount = 0;
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(OFFLINE_CLEANUP_MINUTES);
        for (AiAccount acc : borrowed) {
            // 条件1：借走时间超过 OFFLINE_CLEANUP_MINUTES
            if (acc.getBorrowedAt() != null && acc.getBorrowedAt().isAfter(cutoff)) {
                // 刚借走不久，worker 可能还在跑，跳过
                continue;
            }
            // 条件2：该 worker 在 task_result 里已经没有 RUNNING 单元了
            // 用 countByAssignee（已有的看板方法），但它只统计 RUNNING 的，正好适合
            java.util.List<java.util.Map<String, Object>> stats = taskResultMapper.countByAssignee();
            boolean hasRunning = false;
            for (java.util.Map<String, Object> stat : stats) {
                if (acc.getWorkerId().equals(stat.get("name"))) {
                    hasRunning = true;
                    break;
                }
            }
            if (!hasRunning) {
                int released = aiAccountMapper.releaseAllByWorker(acc.getWorkerId());
                if (released > 0) {
                    releasedCount += released;
                    log.warn("账号池清扫器：worker[{}] 无 RUNNING 单元且借号已超 {} 分钟，释放全部 {} 个账号",
                            acc.getWorkerId(), OFFLINE_CLEANUP_MINUTES, released);
                }
            }
        }
        if (releasedCount > 0) {
            log.info("账号池清扫完成：回收 {} 个账号绑定", releasedCount);
        }
    }
}
