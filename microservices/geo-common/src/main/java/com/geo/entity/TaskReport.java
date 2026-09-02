package com.geo.entity;

import com.baomidou.mybatisplus.annotation.*;

import java.time.LocalDateTime;

/**
 * 【任务分析报告表实体类】
 *
 * 设计思路：
 * 1. 对应数据库表：task_report
 *    每次生成的分析报告存一份历史记录，方便后续做对比分析
 *
 * 2. 为什么Task表已经有reportJson了还要单独建这张表？
 *    - Task里的reportJson只存"最新一次"的报告
 *    - 周期任务（daily/weekly）每次执行都会生成新报告
 *    - 这张表保存所有历史版本，后续做"跟上周对比"等趋势分析要用
 *
 * 3. reportDate字段的作用：
 *    标记这份报告是哪个周期的（比如2024-01-15的日报、2024-W03的周报）
 */
@TableName("task_report")
public class TaskReport {

    // 主键ID，自增
    @TableId(type = IdType.AUTO)
    private Long id;

    // 任务编号
    private String taskNo;
    // 完整的报告JSON数据（和AnalysisReportResponse结构对应）
    private String reportJson;
    // 报告所属周期日期（日报是当天，周报是周一日期）
    private LocalDateTime reportDate;

    // 创建时间，自动填充
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    // ========== getter/setter ==========
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getTaskNo() { return taskNo; }
    public void setTaskNo(String taskNo) { this.taskNo = taskNo; }
    public String getReportJson() { return reportJson; }
    public void setReportJson(String reportJson) { this.reportJson = reportJson; }
    public LocalDateTime getReportDate() { return reportDate; }
    public void setReportDate(LocalDateTime reportDate) { this.reportDate = reportDate; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}