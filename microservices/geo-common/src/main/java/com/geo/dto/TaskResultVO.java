package com.geo.dto;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDateTime;

public class TaskResultVO {

    private Long id;
    private String aiPlatform;
    private String aiDisplayName;
    private String questionText;
    private String answerText;
    private String thinkingContent;
    private String sourceInfo;
    private java.util.List<String> screenshotUrls;
    private String status;
    private String errorMsg;
    private Long durationMs;
    private Integer rpaRetryCount;
    private LocalDateTime createdAt;
    private LocalDateTime completedAt;
    private String showStatus;
    private String brand;
    private LocalDateTime queryTime;
    private java.util.List<String> brandRanking;
    private String sentiment;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getAiPlatform() { return aiPlatform; }
    public void setAiPlatform(String aiPlatform) { this.aiPlatform = aiPlatform; }
    public String getAiDisplayName() { return aiDisplayName; }
    public void setAiDisplayName(String aiDisplayName) { this.aiDisplayName = aiDisplayName; }
    public String getQuestionText() { return questionText; }
    public void setQuestionText(String questionText) { this.questionText = questionText; }
    public String getAnswerText() { return answerText; }
    public void setAnswerText(String answerText) { this.answerText = answerText; }
    public String getThinkingContent() { return thinkingContent; }
    public void setThinkingContent(String thinkingContent) { this.thinkingContent = thinkingContent; }
    public String getSourceInfo() { return sourceInfo; }
    public void setSourceInfo(String sourceInfo) { this.sourceInfo = sourceInfo; }
    public java.util.List<String> getScreenshotUrls() { return screenshotUrls; }
    public void setScreenshotUrls(java.util.List<String> screenshotUrls) { this.screenshotUrls = screenshotUrls; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getErrorMsg() { return errorMsg; }
    public void setErrorMsg(String errorMsg) { this.errorMsg = errorMsg; }
    public Long getDurationMs() { return durationMs; }
    public void setDurationMs(Long durationMs) { this.durationMs = durationMs; }
    public Integer getRpaRetryCount() { return rpaRetryCount; }
    public void setRpaRetryCount(Integer rpaRetryCount) { this.rpaRetryCount = rpaRetryCount; }
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    public LocalDateTime getCompletedAt() { return completedAt; }
    public void setCompletedAt(LocalDateTime completedAt) { this.completedAt = completedAt; }
    public String getShowStatus() { return showStatus; }
    public void setShowStatus(String showStatus) { this.showStatus = showStatus; }
    public String getBrand() { return brand; }
    public void setBrand(String brand) { this.brand = brand; }
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    public LocalDateTime getQueryTime() { return queryTime; }
    public void setQueryTime(LocalDateTime queryTime) { this.queryTime = queryTime; }
    public java.util.List<String> getBrandRanking() { return brandRanking; }
    public void setBrandRanking(java.util.List<String> brandRanking) { this.brandRanking = brandRanking; }
    public String getSentiment() { return sentiment; }
    public void setSentiment(String sentiment) { this.sentiment = sentiment; }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private final TaskResultVO vo = new TaskResultVO();
        public Builder id(Long id) { vo.setId(id); return this; }
        public Builder aiPlatform(String aiPlatform) { vo.setAiPlatform(aiPlatform); return this; }
        public Builder aiDisplayName(String aiDisplayName) { vo.setAiDisplayName(aiDisplayName); return this; }
        public Builder questionText(String questionText) { vo.setQuestionText(questionText); return this; }
        public Builder answerText(String answerText) { vo.setAnswerText(answerText); return this; }
        public Builder thinkingContent(String thinkingContent) { vo.setThinkingContent(thinkingContent); return this; }
        public Builder sourceInfo(String sourceInfo) { vo.setSourceInfo(sourceInfo); return this; }
        public Builder screenshotUrls(java.util.List<String> screenshotUrls) { vo.setScreenshotUrls(screenshotUrls); return this; }
        public Builder status(String status) { vo.setStatus(status); return this; }
        public Builder errorMsg(String errorMsg) { vo.setErrorMsg(errorMsg); return this; }
        public Builder durationMs(Long durationMs) { vo.setDurationMs(durationMs); return this; }
        public Builder rpaRetryCount(Integer rpaRetryCount) { vo.setRpaRetryCount(rpaRetryCount); return this; }
        public Builder createdAt(LocalDateTime createdAt) { vo.setCreatedAt(createdAt); return this; }
        public Builder completedAt(LocalDateTime completedAt) { vo.setCompletedAt(completedAt); return this; }
        public Builder showStatus(String showStatus) { vo.setShowStatus(showStatus); return this; }
        public Builder brand(String brand) { vo.setBrand(brand); return this; }
        public Builder queryTime(LocalDateTime queryTime) { vo.setQueryTime(queryTime); return this; }
        public Builder brandRanking(java.util.List<String> brandRanking) { vo.setBrandRanking(brandRanking); return this; }
        public Builder sentiment(String sentiment) { vo.setSentiment(sentiment); return this; }
        public TaskResultVO build() { return vo; }
    }
}
