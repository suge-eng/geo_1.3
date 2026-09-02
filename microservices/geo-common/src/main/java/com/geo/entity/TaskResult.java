package com.geo.entity;

import com.baomidou.mybatisplus.annotation.*;

import java.time.LocalDateTime;

/**
 * 【任务结果表实体类 - 最核心的一张表】
 *
 * 设计思路：
 * 1. 对应数据库表：task_result
 *    每个"AI平台 × 问题"的组合 = 1条TaskResult记录
 *    举例：选3个平台，10个问题 = 30条TaskResult记录
 *
 * 2. 生命周期状态流转（见ResultStatus枚举）：
 *    PENDING → RUNNING → SUCCESS / FAILED / TIMEOUT
 *
 * 3. 关键字段说明：
 *    - accountId/assignee：这条结果分配给了哪个AI账号和哪台RPA Worker执行
 *    - leaseExpiresAt：租约过期时间（防止Worker挂了没人处理），实现抢占式调度
 *    - thinkingContent：AI的思考过程（比如豆包的"思考中"部分），和answerText分开存
 *    - screenshotUrls：JSON数组，保存RPA机器人操作时截的一张或多张图
 *    - stuckNotified：卡顿是否已经通知过（0=没通知，1=已通知），防止重复报警
 *
 * 4. 这张表是整个系统数据量最大的表，设计时尽量把常用字段放这，
 *    避免查询时join其他表（空间换时间）
 */
@TableName("task_result")
public class TaskResult {

    // 主键ID，自增
    @TableId(type = IdType.AUTO)
    private Long id;

    // 任务ID（关联task表id）
    private Long taskId;
    // 任务编号（冗余字段）
    private String taskNo;
    // 关联的问题ID（task_question表id）
    private Long taskQuestionId;

    // AI平台代码，比如"kimi"
    private String aiPlatform;
    // 问题文本（冗余存一份，不用再join question表查询）
    private String questionText;

    // AI给出的回答正文内容
    private String answerText;
    // AI的思考过程（比如豆包的"思考"部分，有些AI会显示推理过程）
    private String thinkingContent;
    // 来源信息：AI回答里引用了哪些网页/文章（带链接那种）
    private String sourceInfo;
    // 截图URL列表，JSON数组格式：["http://xxx/1.jpg", "http://xxx/2.jpg"]
    private String screenshotUrls;

    // 这条结果的状态（见ResultStatus枚举）：PENDING/RUNNING/SUCCESS/FAILED/TIMEOUT
    private String status;
    // 失败/超时的错误信息描述
    private String errorMsg;
    // 执行耗时，单位毫秒
    private Long durationMs;
    // RPA自动重试次数（首次执行是0，每重试一次+1）
    private Integer rpaRetryCount;

    // ========== 以下字段用于RPA调度和账号分配 ==========
    // 分配使用的AI账号ID（ai_account表）
    private Long accountId;
    // 分配给了哪台RPA Worker（Worker唯一标识）
    private String assignee;
    // 租约过期时间：超过这个时间还没完成，其他Worker可以抢过来执行
    private LocalDateTime leaseExpiresAt;
    // 实际开始执行的时间（RPA Worker领取后设置）
    private LocalDateTime startedAt;
    // 卡顿是否已通知：0=未通知，1=已通知（防止超时任务反复发通知）
    private Integer stuckNotified;

    // 创建时间，自动填充
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    // 完成时间（成功/失败都会设置）
    private LocalDateTime completedAt;

    // ========== getter/setter ==========
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getTaskId() { return taskId; }
    public void setTaskId(Long taskId) { this.taskId = taskId; }
    public String getTaskNo() { return taskNo; }
    public void setTaskNo(String taskNo) { this.taskNo = taskNo; }
    public Long getTaskQuestionId() { return taskQuestionId; }
    public void setTaskQuestionId(Long taskQuestionId) { this.taskQuestionId = taskQuestionId; }
    public String getAiPlatform() { return aiPlatform; }
    public void setAiPlatform(String aiPlatform) { this.aiPlatform = aiPlatform; }
    public String getQuestionText() { return questionText; }
    public void setQuestionText(String questionText) { this.questionText = questionText; }
    public String getAnswerText() { return answerText; }
    public void setAnswerText(String answerText) { this.answerText = answerText; }
    public String getThinkingContent() { return thinkingContent; }
    public void setThinkingContent(String thinkingContent) { this.thinkingContent = thinkingContent; }
    public String getSourceInfo() { return sourceInfo; }
    public void setSourceInfo(String sourceInfo) { this.sourceInfo = sourceInfo; }
    public String getScreenshotUrls() { return screenshotUrls; }
    public void setScreenshotUrls(String screenshotUrls) { this.screenshotUrls = screenshotUrls; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getErrorMsg() { return errorMsg; }
    public void setErrorMsg(String errorMsg) { this.errorMsg = errorMsg; }
    public Long getDurationMs() { return durationMs; }
    public void setDurationMs(Long durationMs) { this.durationMs = durationMs; }
    public Integer getRpaRetryCount() { return rpaRetryCount; }
    public void setRpaRetryCount(Integer rpaRetryCount) { this.rpaRetryCount = rpaRetryCount; }
    public Long getAccountId() { return accountId; }
    public void setAccountId(Long accountId) { this.accountId = accountId; }
    public String getAssignee() { return assignee; }
    public void setAssignee(String assignee) { this.assignee = assignee; }
    public LocalDateTime getLeaseExpiresAt() { return leaseExpiresAt; }
    public void setLeaseExpiresAt(LocalDateTime leaseExpiresAt) { this.leaseExpiresAt = leaseExpiresAt; }
    public LocalDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(LocalDateTime startedAt) { this.startedAt = startedAt; }
    public Integer getStuckNotified() { return stuckNotified; }
    public void setStuckNotified(Integer stuckNotified) { this.stuckNotified = stuckNotified; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getCompletedAt() { return completedAt; }
    public void setCompletedAt(LocalDateTime completedAt) { this.completedAt = completedAt; }
}