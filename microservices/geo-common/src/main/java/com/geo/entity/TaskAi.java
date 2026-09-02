package com.geo.entity;

import com.baomidou.mybatisplus.annotation.*;

import java.time.LocalDateTime;

/**
 * 【任务-AI平台关联表实体类】
 *
 * 设计思路：
 * 1. 对应数据库表：task_ai
 *    记录每个任务选择了哪些AI平台
 *    比如任务1选了豆包、Kimi、DeepSeek三个平台，就会有3条TaskAi记录
 *
 * 2. 为什么要单独建这张表？
 *    - 虽然Task的totalAiCount字段已经记录了"选了几个"，
 *      但不知道"具体选了哪几个"，所以需要这张表存明细
 *    - 同时记录了aiDisplayName（中文名），查询时不用再查枚举
 *
 * 3. 同时存taskId和taskNo的原因：
 *    - taskId：数据库表关联用（主键关联，性能好）
 *    - taskNo：很多场景只传taskNo过来，不用再先查task拿id
 *    这个"冗余"在查询时很省事（空间换时间思想）
 */
@TableName("task_ai")
public class TaskAi {

    // 主键ID，自增
    @TableId(type = IdType.AUTO)
    private Long id;

    // 任务ID（关联task表的id）
    private Long taskId;
    // 任务编号（冗余字段，方便直接查询）
    private String taskNo;

    // AI平台代码（和AiPlatform枚举的code对应），比如"doubao"
    private String aiPlatform;
    // AI平台中文展示名（冗余存一份，不用每次都去枚举查），比如"豆包"
    private String aiDisplayName;

    // 创建时间，MyBatis-Plus自动填充
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    // ========== getter/setter ==========
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getTaskId() { return taskId; }
    public void setTaskId(Long taskId) { this.taskId = taskId; }
    public String getTaskNo() { return taskNo; }
    public void setTaskNo(String taskNo) { this.taskNo = taskNo; }
    public String getAiPlatform() { return aiPlatform; }
    public void setAiPlatform(String aiPlatform) { this.aiPlatform = aiPlatform; }
    public String getAiDisplayName() { return aiDisplayName; }
    public void setAiDisplayName(String aiDisplayName) { this.aiDisplayName = aiDisplayName; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}