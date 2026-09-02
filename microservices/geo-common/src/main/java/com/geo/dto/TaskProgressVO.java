package com.geo.dto;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDateTime;

/**
 * 【任务进度展示VO】
 *
 * 设计思路：
 * 1. 为什么不直接返回Task实体给前端？
 *    - VO（View Object）是专门给前端展示用的数据对象
 *    - Task实体里有很多前端不需要的字段（比如userId、reportJson等）
 *    - VO里可以放一些计算出来的字段（比如percentage百分比）
 *    - 前后端解耦：实体类变了不影响前端，只要VO保持不变就行
 *
 * 2. Builder模式的设计原因：
 *    - 这个类字段很多，如果用构造方法传十几个参数，代码可读性很差
 *    - Builder链式调用：TaskProgressVO.builder().taskNo("xxx").status("xxx").build()
 *    - 想传几个参数就传几个，剩下的用默认值，非常灵活
 */
public class TaskProgressVO {

    // 任务编号（给前端展示用的友好编号）
    private String taskNo;
    // 任务状态：PENDING/PROCESSING/COMPLETED等（见TaskStatus枚举）
    private String status;
    // 总子任务数 = AI平台数 × 问题数
    private Integer totalCount;
    // 已成功完成的子任务数
    private Integer completedCount;
    // 已失败的子任务数
    private Integer failedCount;
    // 选择的AI平台总数
    private Integer totalAiCount;
    // 问题总数
    private Integer totalQuestionCount;
    // 当前正在执行的AI平台名称（给前端显示"正在处理XX平台"）
    private String currentAi;
    // 当前正在执行的问题内容
    private String currentQuestion;
    // 完成百分比：(completedCount + failedCount) / totalCount * 100，直接给前端不用算
    private Double percentage;
    // 任务级别的错误信息（整个任务出错时显示）
    private String errorMsg;
    // 任务创建时间
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createdAt;
    // 任务完成时间（全部子任务结束时设置）
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime completedAt;

    public String getTaskNo() { return taskNo; }
    public void setTaskNo(String taskNo) { this.taskNo = taskNo; }
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
    public String getCurrentAi() { return currentAi; }
    public void setCurrentAi(String currentAi) { this.currentAi = currentAi; }
    public String getCurrentQuestion() { return currentQuestion; }
    public void setCurrentQuestion(String currentQuestion) { this.currentQuestion = currentQuestion; }
    public Double getPercentage() { return percentage; }
    public void setPercentage(Double percentage) { this.percentage = percentage; }
    public String getErrorMsg() { return errorMsg; }
    public void setErrorMsg(String errorMsg) { this.errorMsg = errorMsg; }
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    public LocalDateTime getCompletedAt() { return completedAt; }
    public void setCompletedAt(LocalDateTime completedAt) { this.completedAt = completedAt; }

    /**
     * 创建Builder对象的入口方法
     * 设计思路：静态工厂方法，隐藏new Builder()的细节，写起来更简洁
     */
    public static Builder builder() { return new Builder(); }

    /**
     * 【建造者模式内部类】
     *
     * 设计思路（建造者模式）：
     * 1. 当一个类的字段超过5个时，用构造方法传参会非常混乱
     * 2. 链式调用让代码更清晰，每个方法调用对应一个字段赋值
     * 3. 最后调用build()才真正创建对象，保证对象创建完成时所有需要的字段都已设置
     * 4. return this是链式调用的关键：每个setter方法返回Builder自己，就能继续点下去
     */
    public static class Builder {
        // 内部持有一个VO对象，所有设置都作用在这个对象上
        private final TaskProgressVO vo = new TaskProgressVO();
        public Builder taskNo(String taskNo) { vo.setTaskNo(taskNo); return this; }
        public Builder status(String status) { vo.setStatus(status); return this; }
        public Builder totalCount(Integer totalCount) { vo.setTotalCount(totalCount); return this; }
        public Builder completedCount(Integer completedCount) { vo.setCompletedCount(completedCount); return this; }
        public Builder failedCount(Integer failedCount) { vo.setFailedCount(failedCount); return this; }
        public Builder totalAiCount(Integer totalAiCount) { vo.setTotalAiCount(totalAiCount); return this; }
        public Builder totalQuestionCount(Integer totalQuestionCount) { vo.setTotalQuestionCount(totalQuestionCount); return this; }
        public Builder percentage(Double percentage) { vo.setPercentage(percentage); return this; }
        public Builder errorMsg(String errorMsg) { vo.setErrorMsg(errorMsg); return this; }
        public Builder createdAt(LocalDateTime createdAt) { vo.setCreatedAt(createdAt); return this; }
        public Builder completedAt(LocalDateTime completedAt) { vo.setCompletedAt(completedAt); return this; }
        /** 构建完成，返回最终的VO对象 */
        public TaskProgressVO build() { return vo; }
    }
}