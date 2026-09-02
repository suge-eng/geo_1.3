package com.geo.dto;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDateTime;

/**
 * 【单条任务结果展示VO】
 *
 * 设计思路：
 * 1. TaskResult实体是数据库表的映射，有很多内部调度用的字段（accountId、leaseExpiresAt等）
 * 2. 这个VO是专门给前端看"AI回答结果"的，只保留需要展示的字段
 * 3. 还额外加了一些分析后的字段：sentiment（情感分析结果）、brandRanking（品牌排名提取）等
 *
 * 跟TaskResult实体的区别：
 * - 实体是给后端自己用的，VO是给前端展示用的
 * - VO里的screenshotUrls是List<String>（实体里是JSON字符串，要转成列表）
 * - VO加了showStatus等前端友好的展示字段
 */
public class TaskResultVO {

    // TaskResult主键ID
    private Long id;
    // AI平台代码（如"kimi"）
    private String aiPlatform;
    // AI平台中文展示名（如"Kimi"）
    private String aiDisplayName;
    // 问题文本内容
    private String questionText;
    // AI给出的回答正文
    private String answerText;
    // AI的思考过程（比如豆包的"思考"部分）
    private String thinkingContent;
    // AI回答里引用的来源信息（带链接的那种引用）
    private String sourceInfo;
    // 截图URL列表（从实体的JSON字符串解析成List）
    private java.util.List<String> screenshotUrls;
    // 结果状态（PENDING/RUNNING/SUCCESS/FAILED/TIMEOUT）
    private String status;
    // 错误信息（失败/超时时显示）
    private String errorMsg;
    // 执行耗时（毫秒）
    private Long durationMs;
    // RPA重试次数
    private Integer rpaRetryCount;
    // 创建时间
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createdAt;
    // 完成时间
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime completedAt;
    // 前端展示用的状态文字（比如"成功"、"失败"，不是英文枚举值）
    private String showStatus;
    // 关联的品牌名（兼容老字段）
    private String brand;
    // 查询时间（兼容老字段）
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime queryTime;
    // 品牌排名列表（从回答里提取出来的品牌推荐顺序）
    private java.util.List<String> brandRanking;
    // 情感分析结果：positive/negative/neutral/none
    private String sentiment;
    // 情感分析的参考依据片段（前端可以展示"基于这句话判断为正面"）
    private String sentimentSource;

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
    public String getSentimentSource() { return sentimentSource; }
    public void setSentimentSource(String sentimentSource) { this.sentimentSource = sentimentSource; }

    /** 创建Builder对象入口 */
    public static Builder builder() { return new Builder(); }

    /**
     * 【建造者模式内部类】
     * 设计思路同TaskProgressVO.Builder：字段太多，用链式调用更清晰
     */
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
        public Builder sentimentSource(String sentimentSource) { vo.setSentimentSource(sentimentSource); return this; }
        public TaskResultVO build() { return vo; }
    }
}