package com.geo.dto;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDateTime;

public class TaskProgressVO {

    private String taskNo;
    private String status;
    private Integer totalCount;
    private Integer completedCount;
    private Integer failedCount;
    private Integer totalAiCount;
    private Integer totalQuestionCount;
    private String currentAi;
    private String currentQuestion;
    private Double percentage;
    private String errorMsg;
    private LocalDateTime createdAt;
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

    public static Builder builder() { return new Builder(); }

    public static class Builder {
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
        public TaskProgressVO build() { return vo; }
    }
}
