package com.geo.entity;

import com.baomidou.mybatisplus.annotation.*;

import java.time.LocalDateTime;

/**
 * 【任务-问题列表关联表实体类】
 *
 * 设计思路：
 * 1. 对应数据库表：task_question
 *    记录每个任务包含哪些问题，按sortOrder排序
 *    一个问题会被每个AI平台都答一遍（笛卡尔积思想）
 *
 * 2. sortOrder字段的作用：
 *    记录用户输入问题时的顺序，前端展示要按用户给的顺序排，
 *    不能依赖id排序（因为竞对品牌的问题是后来插进去的，sortOrder=999）
 *
 * 3. 竞对品牌问题的特殊处理（TaskService.saveTaskCompetitors方法里）：
 *    比如用户加了竞争对手"小米"，系统会自动生成一个特殊问题：
 *    "竞争对手: 小米"，sortOrder设为999，排在普通问题的后面
 */
@TableName("task_question")
public class TaskQuestion {

    // 主键ID，自增
    @TableId(type = IdType.AUTO)
    private Long id;

    // 任务ID（关联task表id）
    private Long taskId;
    // 任务编号（冗余字段，方便查询）
    private String taskNo;

    // 问题文本内容，比如"推荐几款拍照好的手机"
    private String questionText;
    // 排序顺序（用户输入的顺序从0开始，竞对问题排999放最后）
    private Integer sortOrder;

    // 创建时间，自动填充
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    // ========== getter/setter ==========
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getTaskId() { return taskId; }
    public void setTaskId(Long taskId) { this.taskId = taskId; }
    public String getTaskNo() { return taskNo; }
    public void setTaskNo(String taskNo) { this.taskNo = taskNo; }
    public String getQuestionText() { return questionText; }
    public void setQuestionText(String questionText) { this.questionText = questionText; }
    public Integer getSortOrder() { return sortOrder; }
    public void setSortOrder(Integer sortOrder) { this.sortOrder = sortOrder; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}