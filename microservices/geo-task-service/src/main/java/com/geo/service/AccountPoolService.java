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

@Service
public class AccountPoolService {

    private static final Logger log = LoggerFactory.getLogger(AccountPoolService.class);

    private final AiAccountMapper aiAccountMapper;

    public AccountPoolService(AiAccountMapper aiAccountMapper) {
        this.aiAccountMapper = aiAccountMapper;
    }

    @Transactional
    public AiAccount acquireAccount(String platform) {
        resetDailyCountersIfNeeded();

        AiAccount account = aiAccountMapper.selectBestAccount(platform);
        if (account == null) {
            throw new BusinessException(ResultCode.ACCOUNT_EXHAUSTED, "平台 " + platform + " 暂无可用账号");
        }

        boolean acquired = tryAcquireAccount(account);
        if (!acquired) {
            List<AiAccount> accounts = aiAccountMapper.selectAvailableAccounts(platform);
            for (AiAccount acc : accounts) {
                if (tryAcquireAccount(acc)) {
                    return acc;
                }
            }
            throw new BusinessException(ResultCode.ACCOUNT_EXHAUSTED, "平台 " + platform + " 账号暂时不可用");
        }

        return account;
    }

    private boolean tryAcquireAccount(AiAccount account) {
        if (!AccountStatus.ACTIVE.name().equals(account.getStatus())) {
            return false;
        }

        if (account.getCooldownUntil() != null && account.getCooldownUntil().isAfter(LocalDateTime.now())) {
            return false;
        }

        if (account.getDailyUsed() >= account.getDailyLimit()) {
            updateAccountStatus(account.getId(), AccountStatus.EXHAUSTED.name(), LocalDateTime.now().plusDays(1));
            return false;
        }

        if (account.getConsecutiveFailures() >= account.getMaxConsecutiveFailures()) {
            updateAccountStatus(account.getId(), AccountStatus.MAINTENANCE.name(), LocalDateTime.now().plusHours(1));
            return false;
        }

        if (account.getLastRequestAt() != null) {
            LocalDateTime nextAllowedTime = account.getLastRequestAt()
                    .plusNanos(account.getRequestIntervalMs() * 1_000_000L);
            if (nextAllowedTime.isAfter(LocalDateTime.now())) {
                return false;
            }
        }

        aiAccountMapper.incrementDailyUsed(account.getId());
        return true;
    }

    @Transactional
    public void markAccountSuccess(Long accountId) {
        aiAccountMapper.resetFailureCount(accountId);
    }

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

    @Transactional
    public void banAccount(Long accountId) {
        updateAccountStatus(accountId, AccountStatus.BANNED.name(), null);
    }

    @Transactional
    public void unbanAccount(Long accountId) {
        updateAccountStatus(accountId, AccountStatus.ACTIVE.name(), null);
    }

    private void updateAccountStatus(Long accountId, String status, LocalDateTime cooldownUntil) {
        aiAccountMapper.updateStatusAndCooldown(accountId, status, cooldownUntil);
    }

    @Transactional
    public void resetDailyCountersIfNeeded() {
        LocalDateTime midnight = LocalDateTime.now().toLocalDate().atStartOfDay().plusDays(1);
        aiAccountMapper.resetDailyUsed(midnight);
    }

    @Transactional
    public AiAccount addAccount(AiAccount account) {
        account.setStatus(AccountStatus.ACTIVE.name());
        account.setDailyUsed(0);
        account.setConsecutiveFailures(0);
        aiAccountMapper.insert(account);
        return account;
    }
}
