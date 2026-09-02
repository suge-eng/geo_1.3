package com.geo.service;

import com.geo.common.BusinessException;
import com.geo.common.ResultCode;
import com.geo.entity.AiAccount;
import com.geo.enums.AccountStatus;
import com.geo.mapper.AiAccountMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 【AI平台账号池调度服务】
 *
 * 设计思路：
 * 这个类是"多账号轮流使用"策略的大脑。RPA Worker要执行任务前，必须先从这里"借"一个账号。
 *
 * ============= 账号借用流程（核心）=============
 * 1. acquireAccount(platform) 借一个该平台的账号
 *    a. 先尝试借"最优账号"（SQL里按优先级排好的那个）
 *    b. 如果最优的不符合条件（正在冷却/超额度/间隔不够），就遍历全部账号一个个试
 *    c. 所有都不行就抛 ACCOUNT_EXHAUSTED 异常（告诉调用方：这个平台暂时没号可用了）
 *
 * 2. tryAcquireAccount 对单个账号做5层资格检查：
 *    ① 状态是不是 ACTIVE？
 *    ② 有没有在冷却期（被风控了，暂时不能用）？
 *    ③ 今日使用次数到上限了吗？→ 到了就把状态改成 EXHAUSTED，明天自动恢复
 *    ④ 连续失败次数到上限了吗？→ 到了就进 MAINTENANCE 冷却1小时
 *    ⑤ 距离上次使用间隔够不够？→ 不够说明操作太频繁，容易被封号
 *    都通过了 → dailyUsed+1，借走成功。
 *
 * 3. 使用完成后的回调：
 *    - markAccountSuccess：成功 → 清零失败计数（账号健康状态恢复）
 *    - markAccountFailure：失败 → 失败计数+1，连续多次就进冷却
 */
@Service
public class AccountPoolService {

    private static final Logger log = LoggerFactory.getLogger(AccountPoolService.class);

    private final AiAccountMapper aiAccountMapper;

    public AccountPoolService(AiAccountMapper aiAccountMapper) {
        this.aiAccountMapper = aiAccountMapper;
    }

    /**
     * 【借账号】核心入口：从指定平台借一个健康的账号
     * 策略：先选最优→最优不行就遍历所有→实在没有就报错
     */
    @Transactional
    public AiAccount acquireAccount(String platform) {
        // 每次借之前先顺手检查一下"是否过了凌晨需要重置日额度"
        resetDailyCountersIfNeeded();

        // 第一步：拿SQL里排序好的"最优账号"先试
        AiAccount account = aiAccountMapper.selectBestAccount(platform);
        if (account == null) {
            throw new BusinessException(ResultCode.ACCOUNT_EXHAUSTED, "平台 " + platform + " 暂无可用账号");
        }

        boolean acquired = tryAcquireAccount(account);
        if (!acquired) {
            // 第二步：最优不行就遍历所有可用账号一个个试
            List<AiAccount> accounts = aiAccountMapper.selectAvailableAccounts(platform);
            for (AiAccount acc : accounts) {
                if (tryAcquireAccount(acc)) {
                    return acc;
                }
            }
            // 全部试过都不行，抛出业务异常
            throw new BusinessException(ResultCode.ACCOUNT_EXHAUSTED, "平台 " + platform + " 账号暂时不可用");
        }

        return account;
    }

    /**
     * 【单个账号资格检查】5层过滤，全通过就dailyUsed+1。
     * 返回true=借走成功，false=不符合条件。
     */
    private boolean tryAcquireAccount(AiAccount account) {
        // 第1层：状态必须是 ACTIVE
        if (!AccountStatus.ACTIVE.name().equals(account.getStatus())) {
            return false;
        }

        // 第2层：如果还在冷却期（风控时间没到），不能用
        if (account.getCooldownUntil() != null && account.getCooldownUntil().isAfter(LocalDateTime.now())) {
            return false;
        }

        // 第3层：今日使用量超上限 → 标记EXHAUSTED，明天自动好
        if (account.getDailyUsed() >= account.getDailyLimit()) {
            updateAccountStatus(account.getId(), AccountStatus.EXHAUSTED.name(), LocalDateTime.now().plusDays(1));
            return false;
        }

        // 第4层：连续失败太多次 → 进MAINTENANCE冷却1小时
        if (account.getConsecutiveFailures() >= account.getMaxConsecutiveFailures()) {
            updateAccountStatus(account.getId(), AccountStatus.MAINTENANCE.name(), LocalDateTime.now().plusHours(1));
            return false;
        }

        // 第5层：两次请求间隔必须够（模拟真人操作频率，防封号）
        if (account.getLastRequestAt() != null) {
            LocalDateTime nextAllowedTime = account.getLastRequestAt()
                    .plusNanos(account.getRequestIntervalMs() * 1_000_000L);
            if (nextAllowedTime.isAfter(LocalDateTime.now())) {
                return false;
            }
        }

        // 全部检查通过 → 今日使用次数+1，账号借走成功
        aiAccountMapper.incrementDailyUsed(account.getId());
        return true;
    }

    /**
     * 【使用成功回调】清零连续失败计数 → 账号从"生病边缘"恢复健康
     */
    @Transactional
    public void markAccountSuccess(Long accountId) {
        aiAccountMapper.resetFailureCount(accountId);
    }

    /**
     * 【使用失败回调】连续失败+1，到阈值就进维护冷却
     */
    @Transactional
    public void markAccountFailure(Long accountId) {
        AiAccount account = aiAccountMapper.selectById(accountId);
        if (account != null) {
            aiAccountMapper.incrementFailureCount(accountId);
            // 注意判断时+1：因为刚才+1了，但内存里account的字段还没刷新（读时是旧值）
            if (account.getConsecutiveFailures() + 1 >= account.getMaxConsecutiveFailures()) {
                updateAccountStatus(accountId, AccountStatus.MAINTENANCE.name(), LocalDateTime.now().plusHours(1));
            }
        }
    }

    /**
     * 【人工封号】账号被平台封了，永久停用（除非人工解禁）
     */
    @Transactional
    public void banAccount(Long accountId) {
        updateAccountStatus(accountId, AccountStatus.BANNED.name(), null);
    }

    /**
     * 【人工解封】运维手动把封禁的账号恢复
     */
    @Transactional
    public void unbanAccount(Long accountId) {
        updateAccountStatus(accountId, AccountStatus.ACTIVE.name(), null);
    }

    /** 私有工具方法：更新账号状态和冷却时间 */
    private void updateAccountStatus(Long accountId, String status, LocalDateTime cooldownUntil) {
        aiAccountMapper.updateStatusAndCooldown(accountId, status, cooldownUntil);
    }

    /**
     * 【日额度重置】每次借账号时顺手检查一次：过了凌晨就把所有账号的dailyUsed归零。
     * 巧妙之处：不用额外开定时任务，借用请求触发即可。明天0点第一次借账号时自动触发。
     */
    @Transactional
    public void resetDailyCountersIfNeeded() {
        // 下一天的0点（作为"新的一天"的标记时间）
        LocalDateTime midnight = LocalDateTime.now().toLocalDate().atStartOfDay().plusDays(1);
        aiAccountMapper.resetDailyUsed(midnight);
    }

    /**
     * 【新增账号】初始化必填字段后插入数据库
     */
    @Transactional
    public AiAccount addAccount(AiAccount account) {
        account.setStatus(AccountStatus.ACTIVE.name());
        account.setDailyUsed(0);
        account.setConsecutiveFailures(0);
        aiAccountMapper.insert(account);
        return account;
    }
}