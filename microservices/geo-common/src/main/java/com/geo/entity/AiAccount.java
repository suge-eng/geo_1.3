package com.geo.entity;

import com.baomidou.mybatisplus.annotation.*;

import java.time.LocalDateTime;

@TableName("ai_account")
public class AiAccount {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String platform;
    private String accountName;
    private String cookie;
    private String status;
    private Integer dailyLimit;
    private Integer dailyUsed;
    private LocalDateTime dailyResetAt;
    private LocalDateTime lastRequestAt;
    private LocalDateTime cooldownUntil;
    private Integer requestIntervalMs;
    private Integer consecutiveFailures;
    private Integer maxConsecutiveFailures;
    private Integer priority;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getPlatform() { return platform; }
    public void setPlatform(String platform) { this.platform = platform; }
    public String getAccountName() { return accountName; }
    public void setAccountName(String accountName) { this.accountName = accountName; }
    public String getCookie() { return cookie; }
    public void setCookie(String cookie) { this.cookie = cookie; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Integer getDailyLimit() { return dailyLimit; }
    public void setDailyLimit(Integer dailyLimit) { this.dailyLimit = dailyLimit; }
    public Integer getDailyUsed() { return dailyUsed; }
    public void setDailyUsed(Integer dailyUsed) { this.dailyUsed = dailyUsed; }
    public LocalDateTime getDailyResetAt() { return dailyResetAt; }
    public void setDailyResetAt(LocalDateTime dailyResetAt) { this.dailyResetAt = dailyResetAt; }
    public LocalDateTime getLastRequestAt() { return lastRequestAt; }
    public void setLastRequestAt(LocalDateTime lastRequestAt) { this.lastRequestAt = lastRequestAt; }
    public LocalDateTime getCooldownUntil() { return cooldownUntil; }
    public void setCooldownUntil(LocalDateTime cooldownUntil) { this.cooldownUntil = cooldownUntil; }
    public Integer getRequestIntervalMs() { return requestIntervalMs; }
    public void setRequestIntervalMs(Integer requestIntervalMs) { this.requestIntervalMs = requestIntervalMs; }
    public Integer getConsecutiveFailures() { return consecutiveFailures; }
    public void setConsecutiveFailures(Integer consecutiveFailures) { this.consecutiveFailures = consecutiveFailures; }
    public Integer getMaxConsecutiveFailures() { return maxConsecutiveFailures; }
    public void setMaxConsecutiveFailures(Integer maxConsecutiveFailures) { this.maxConsecutiveFailures = maxConsecutiveFailures; }
    public Integer getPriority() { return priority; }
    public void setPriority(Integer priority) { this.priority = priority; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
