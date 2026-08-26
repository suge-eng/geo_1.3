package com.geo.dto;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public class TaskRankingVO {

    private String taskNo;
    private String title;
    private String brandName;
    private List<String> competitorBrands;
    private List<QuestionRanking> questionRankings;
    private List<BrandAggregatedRanking> aggregatedRankings;
    private Integer totalAnswers;
    private Integer validAnswers;

    public String getTaskNo() { return taskNo; }
    public void setTaskNo(String taskNo) { this.taskNo = taskNo; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getBrandName() { return brandName; }
    public void setBrandName(String brandName) { this.brandName = brandName; }
    public List<String> getCompetitorBrands() { return competitorBrands; }
    public void setCompetitorBrands(List<String> competitorBrands) { this.competitorBrands = competitorBrands; }
    public List<QuestionRanking> getQuestionRankings() { return questionRankings; }
    public void setQuestionRankings(List<QuestionRanking> questionRankings) { this.questionRankings = questionRankings; }
    public List<BrandAggregatedRanking> getAggregatedRankings() { return aggregatedRankings; }
    public void setAggregatedRankings(List<BrandAggregatedRanking> aggregatedRankings) { this.aggregatedRankings = aggregatedRankings; }
    public Integer getTotalAnswers() { return totalAnswers; }
    public void setTotalAnswers(Integer totalAnswers) { this.totalAnswers = totalAnswers; }
    public Integer getValidAnswers() { return validAnswers; }
    public void setValidAnswers(Integer validAnswers) { this.validAnswers = validAnswers; }

    public static class QuestionRanking {
        private Long resultId;
        private Long questionId;
        private String questionText;
        private String aiPlatform;
        private String aiDisplayName;
        private String answerText;
        private List<BrandRankItem> brandRankings;
        private Integer sortOrder;
        private String status;
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        private LocalDateTime completedAt;

        public Long getResultId() { return resultId; }
        public void setResultId(Long resultId) { this.resultId = resultId; }
        public Long getQuestionId() { return questionId; }
        public void setQuestionId(Long questionId) { this.questionId = questionId; }
        public String getQuestionText() { return questionText; }
        public void setQuestionText(String questionText) { this.questionText = questionText; }
        public String getAiPlatform() { return aiPlatform; }
        public void setAiPlatform(String aiPlatform) { this.aiPlatform = aiPlatform; }
        public String getAiDisplayName() { return aiDisplayName; }
        public void setAiDisplayName(String aiDisplayName) { this.aiDisplayName = aiDisplayName; }
        public String getAnswerText() { return answerText; }
        public void setAnswerText(String answerText) { this.answerText = answerText; }
        public List<BrandRankItem> getBrandRankings() { return brandRankings; }
        public void setBrandRankings(List<BrandRankItem> brandRankings) { this.brandRankings = brandRankings; }
        public Integer getSortOrder() { return sortOrder; }
        public void setSortOrder(Integer sortOrder) { this.sortOrder = sortOrder; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public LocalDateTime getCompletedAt() { return completedAt; }
        public void setCompletedAt(LocalDateTime completedAt) { this.completedAt = completedAt; }
    }

    public static class BrandRankItem {
        private String brandName;
        private Boolean isSelfBrand;
        private Integer rank;
        private Integer firstIndex;
        private String matchedText;

        public BrandRankItem() {}

        public BrandRankItem(String brandName, Boolean isSelfBrand, Integer rank, Integer firstIndex, String matchedText) {
            this.brandName = brandName;
            this.isSelfBrand = isSelfBrand;
            this.rank = rank;
            this.firstIndex = firstIndex;
            this.matchedText = matchedText;
        }

        public String getBrandName() { return brandName; }
        public void setBrandName(String brandName) { this.brandName = brandName; }
        public Boolean getIsSelfBrand() { return isSelfBrand; }
        public void setIsSelfBrand(Boolean isSelfBrand) { this.isSelfBrand = isSelfBrand; }
        public Integer getRank() { return rank; }
        public void setRank(Integer rank) { this.rank = rank; }
        public Integer getFirstIndex() { return firstIndex; }
        public void setFirstIndex(Integer firstIndex) { this.firstIndex = firstIndex; }
        public String getMatchedText() { return matchedText; }
        public void setMatchedText(String matchedText) { this.matchedText = matchedText; }
    }

    public static class BrandAggregatedRanking {
        private String brandName;
        private Boolean isSelfBrand;
        private Integer mentionCount;
        private Integer firstCount;
        private Integer top3Count;
        private Integer top5Count;
        private Double mentionRate;
        private Double firstRate;
        private Double top3Rate;
        private Double top5Rate;
        private Map<Integer, Integer> rankDistribution;
        private List<Integer> allRanks;

        public String getBrandName() { return brandName; }
        public void setBrandName(String brandName) { this.brandName = brandName; }
        public Boolean getIsSelfBrand() { return isSelfBrand; }
        public void setIsSelfBrand(Boolean isSelfBrand) { this.isSelfBrand = isSelfBrand; }
        public Integer getMentionCount() { return mentionCount; }
        public void setMentionCount(Integer mentionCount) { this.mentionCount = mentionCount; }
        public Integer getFirstCount() { return firstCount; }
        public void setFirstCount(Integer firstCount) { this.firstCount = firstCount; }
        public Integer getTop3Count() { return top3Count; }
        public void setTop3Count(Integer top3Count) { this.top3Count = top3Count; }
        public Integer getTop5Count() { return top5Count; }
        public void setTop5Count(Integer top5Count) { this.top5Count = top5Count; }
        public Double getMentionRate() { return mentionRate; }
        public void setMentionRate(Double mentionRate) { this.mentionRate = mentionRate; }
        public Double getFirstRate() { return firstRate; }
        public void setFirstRate(Double firstRate) { this.firstRate = firstRate; }
        public Double getTop3Rate() { return top3Rate; }
        public void setTop3Rate(Double top3Rate) { this.top3Rate = top3Rate; }
        public Double getTop5Rate() { return top5Rate; }
        public void setTop5Rate(Double top5Rate) { this.top5Rate = top5Rate; }
        public Map<Integer, Integer> getRankDistribution() { return rankDistribution; }
        public void setRankDistribution(Map<Integer, Integer> rankDistribution) { this.rankDistribution = rankDistribution; }
        public List<Integer> getAllRanks() { return allRanks; }
        public void setAllRanks(List<Integer> allRanks) { this.allRanks = allRanks; }
    }
}
