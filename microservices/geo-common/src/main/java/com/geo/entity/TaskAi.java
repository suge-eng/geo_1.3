package com.geo.entity;

import com.baomidou.mybatisplus.annotation.*;

import java.time.LocalDateTime;

@TableName("task_ai")
public class TaskAi {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long taskId;
    private String taskNo;
    private String aiPlatform;
    private String aiDisplayName;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

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
