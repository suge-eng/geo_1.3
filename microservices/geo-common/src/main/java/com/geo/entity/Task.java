package com.geo.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDateTime;

/**
 * 【任务主表实体类】
 *
 * 设计思路：
 * 1. 对应数据库表名：task
 *    一个Task = 用户创建的一次完整的AI对比任务
 *    包含：选了哪些AI平台、要问什么问题、自主品牌和竞对是什么等
 *
 * 2. 表关系：
 *    Task（1） →  TaskAi（N）        ：一个任务选了多个AI平台
 *    Task（1） →  TaskQuestion（N）  ：一个任务包含多个问题
 *    Task（1） →  TaskResult（N）    ：一个任务产生N条结果（平台数×问题数）
 *
 * 3. 几个关键字段的设计原因：
 *    - taskNo：业务唯一编号（T+时间戳+UUID），给前端展示用，比id友好
 *    - competitors存JSON字符串：方便直接序列化反序列化，不用额外的关联表
 *    - retryOnFailure、scope等用exist=false：这些是临时字段，不存数据库
 *
 * 4. @TableField(fill=FieldFill.INSERT) 的作用：
 *    MyBatis-Plus自动填充创建时间、更新时间，不用每次手动set
 */
@TableName("task")
public class Task {

    // 主键ID，数据库自增
    @TableId(type = IdType.AUTO)
    private Long id;

    // 任务业务编号（T+时间戳+UUID短码），给用户看的友好编号
    private String taskNo;

    // 创建这个任务的用户ID，暂时写死anonymous（匿名），后续接用户系统再改
    private String userId;

    // 任务标题，用户自己起的名字，比如"华为Mate60竞品分析"
    private String title;

    // 任务状态（见TaskStatus枚举）：PENDING/PROCESSING/COMPLETED等
    private String status;

    // 总子任务数 = AI平台数 × 问题数
    private Integer totalCount;
    // 已成功完成的子任务数
    private Integer completedCount;
    // 已失败的子任务数
    private Integer failedCount;
    // AI平台总数（方便前端展示不用重新算）
    private Integer totalAiCount;
    // 问题总数（方便前端展示不用重新算）
    private Integer totalQuestionCount;

    // 要分析的自主品牌名，比如"华为"
    private String brandName;
    // 具体单品名，比如"华为Mate 60 Pro"
    private String productName;

    // 竞争对手品牌列表，存JSON数组字符串：["小米","苹果","OPPO"]
    // 设计：用JSON存而不是单独建表，简单且查询效率高
    private String competitors;

    // 分析报告的完整JSON数据，任务全部完成后AnalysisService生成后存进来
    private String reportJson;

    // 执行频率：single=只跑一次，daily=每天，weekly=每周
    private String executionFrequency;

    // 失败时是否自动重试（这是请求参数，不存数据库，只用一次）
    @TableField(exist = false)
    private Boolean retryOnFailure;

    // 下次自动执行时间（周期任务用），计算好存在这，定时任务扫描这个字段
    private LocalDateTime nextRunTime;

    // 整个任务级别的错误信息（不是单个子任务的错）
    private String errorMsg;

    // 白名单URL列表（JSON数组字符串），RPA搜索时只访问这些域名
    private String whitelistUrls;

    // ========== 以下三个是@TableField(exist=false)，仅用于前端展示，不存数据库 ==========
    // 任务范围标识：LOCAL=普通任务，以后可能扩展GLOBAL=全局分析任务
    @TableField(exist = false)
    private String scope;
    // 意图数量（NLP后续功能，统计问题中的意图数）
    @TableField(exist = false)
    private Integer intentCount;
    // 问题数量（冗余字段，兼容老数据）
    @TableField(exist = false)
    private Integer questionCount;

    // 创建时间：MyBatis-Plus自动填充（新增时自动写入当前时间）
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    // 更新时间：MyBatis-Plus自动填充（新增和修改时自动更新）
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    // 任务全部完成的时间（所有子任务都成功/失败时设置）
    private LocalDateTime completedAt;

    // ==================== 以下全是getter/setter ====================
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getTaskNo() { return taskNo; }
    public void setTaskNo(String taskNo) { this.taskNo = taskNo; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Integer getTotalCount() { return totalCount; }
    public void setTotalCount(Integer totalCount) { this.totalCount = totalCount; }
    public Integer getCompletedCount() { return completedCount; }
    public void setCompletedCount(Integer completedCount) { this.completedCount = completedCount; }
    public Integer getFailedCount() { return failedCount; }
    public void setFailedCount(Integer failedCount) { this.failedCount = failedCount; }
    public Integer getTotalAiCount() { return totalAiCount; }
    public void setTotalAiCount(Integer totalAiCount) { this.totalAiCount = totalAiCount; }
    public Integer getTotalQuestionCount() { return totalQuestionCount; }
    public void setTotalQuestionCount(Integer totalQuestionCount) { this.totalQuestionCount = totalQuestionCount; }
    public String getBrandName() { return brandName; }
    public void setBrandName(String brandName) { this.brandName = brandName; }
    public String getProductName() { return productName; }
    public void setProductName(String productName) { this.productName = productName; }
    public String getCompetitors() { return competitors; }
    public void setCompetitors(String competitors) { this.competitors = competitors; }
    public String getReportJson() { return reportJson; }
    public void setReportJson(String reportJson) { this.reportJson = reportJson; }
    public String getExecutionFrequency() { return executionFrequency; }
    public void setExecutionFrequency(String executionFrequency) { this.executionFrequency = executionFrequency; }
    public Boolean getRetryOnFailure() { return retryOnFailure; }
    public void setRetryOnFailure(Boolean retryOnFailure) { this.retryOnFailure = retryOnFailure; }
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    public LocalDateTime getNextRunTime() { return nextRunTime; }
    public void setNextRunTime(LocalDateTime nextRunTime) { this.nextRunTime = nextRunTime; }
    public String getErrorMsg() { return errorMsg; }
    public void setErrorMsg(String errorMsg) { this.errorMsg = errorMsg; }
    public String getWhitelistUrls() { return whitelistUrls; }
    public void setWhitelistUrls(String whitelistUrls) { this.whitelistUrls = whitelistUrls; }
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    public LocalDateTime getCompletedAt() { return completedAt; }
    public void setCompletedAt(LocalDateTime completedAt) { this.completedAt = completedAt; }

    public String getScope() { return scope; }
    public void setScope(String scope) { this.scope = scope; }
    public Integer getIntentCount() { return intentCount; }
    public void setIntentCount(Integer intentCount) { this.intentCount = intentCount; }
    public Integer getQuestionCount() { return questionCount; }
    public void setQuestionCount(Integer questionCount) { this.questionCount = questionCount; }
}