package com.geo.entity;

import com.baomidou.mybatisplus.annotation.*;

import java.time.LocalDateTime;

/**
 * 【AI平台账号池实体类】
 *
 * 设计思路：
 * 1. 对应数据库表：ai_account
 *    这个系统不是通过API调AI，而是模拟真人用浏览器访问AI网页版，
 *    所以需要真实的账号（带Cookie），多个账号轮换使用避免被风控。
 *
 * 2. 账号调度策略（核心字段）：
 *    - status：ACTIVE才会被调度使用
 *    - dailyLimit / dailyUsed：每天使用额度，超过变EXHAUSTED
 *    - dailyResetAt：额度重置时间（一般第二天凌晨）
 *    - requestIntervalMs：两个请求之间的间隔（毫秒），模拟真人操作频率，防封号
 *    - consecutiveFailures：连续失败次数，超过maxConsecutiveFailures就把账号变MAINTENANCE
 *    - cooldownUntil：账号的冷却截止时间，触发风控后冷却一段时间再用
 *    - priority：账号优先级，数字越小优先被选（比如会员账号priority=1优先用）
 */
@TableName("ai_account")
public class AiAccount {

    // 主键ID，自增
    @TableId(type = IdType.AUTO)
    private Long id;

    // 所属AI平台（和AiPlatform枚举对应），比如"doubao"
    private String platform;
    // 账号名称（备注用，比如"手机号138xxxx"）
    private String accountName;
    // 登录凭证Cookie（JSON字符串或整段Cookie Header），RPA机器人登录用
    private String cookie;
    // 账号状态（见AccountStatus枚举）：ACTIVE/BANNED/MAINTENANCE/EXHAUSTED
    private String status;

    // 每日可使用次数上限
    private Integer dailyLimit;
    // 今日已使用次数
    private Integer dailyUsed;
    // 下次额度重置时间（过了这个时间dailyUsed归零）
    private LocalDateTime dailyResetAt;

    // 上次发起请求时间（计算间隔用）
    private LocalDateTime lastRequestAt;
    // 账号冷却截止时间（触发风控后，这段时间内不使用）
    private LocalDateTime cooldownUntil;

    // 两次请求间隔（毫秒），防止请求太快被平台识别为机器人
    private Integer requestIntervalMs;
    // 连续失败次数（失败+1，成功归零）
    private Integer consecutiveFailures;
    // 连续失败多少次就把账号暂停维护
    private Integer maxConsecutiveFailures;
    // 优先级：数字越小越优先被选中使用（1最优先）
    private Integer priority;

    // ========== worker 绑定相关字段（新增） ==========
    // 当前占用该账号的 worker 标识（机器名），为空表示空闲可分配。
    // 防止多个 worker 同时抢同一个账号。
    private String workerId;
    // 当前批次已处理的问题数量：worker 借到账号后，每处理完一个 +1，
    // 达到 BATCH_SIZE（如10）后自动释放账号重新借下一个。
    private Integer batchCount;
    // 账号被借走的时间戳（用于统计/诊断）
    private LocalDateTime borrowedAt;

    // 创建时间，自动填充
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
    // 更新时间，自动填充
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    // ========== getter/setter ==========
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
    public String getWorkerId() { return workerId; }
    public void setWorkerId(String workerId) { this.workerId = workerId; }
    public Integer getBatchCount() { return batchCount; }
    public void setBatchCount(Integer batchCount) { this.batchCount = batchCount; }
    public LocalDateTime getBorrowedAt() { return borrowedAt; }
    public void setBorrowedAt(LocalDateTime borrowedAt) { this.borrowedAt = borrowedAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}