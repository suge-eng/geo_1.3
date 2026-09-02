package com.geo.dto;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 【品牌排名分析结果VO】
 *
 * 设计思路：
 * 1. 这个VO的作用是把"AI回答中各品牌被提到的排名"做结构化分析后返回给前端
 *    比如用户问"推荐几款拍照好的手机"，AI回答里推荐了华为、小米、苹果，
 *    我们就把这些品牌按出现顺序提取出来，做排名统计。
 *
 * 2. 两层排名结构：
 *    - questionRankings：每个问题×每个AI平台 的单条回答排名明细（细粒度）
 *    - aggregatedRankings：所有回答汇总后的品牌综合排名（粗粒度，总体统计）
 *
 * 3. 为什么要分两层？
 *    - 前端可以先展示"总体排名"给用户一个宏观印象
 *    - 用户点进去可以看"某条具体回答里的排名"，支持钻取分析
 */
public class TaskRankingVO {

    // 任务编号
    private String taskNo;
    // 任务标题
    private String title;
    // 自主品牌名称（我们要分析的目标品牌）
    private String brandName;
    // 竞争对手品牌列表（从Task.competitors解析出来的JSON数组）
    private List<String> competitorBrands;
    // 每个问题的排名明细（1个问题×N个AI平台 = N条记录）
    private List<QuestionRanking> questionRankings;
    // 品牌综合汇总排名（所有回答统计后的结果）
    private List<BrandAggregatedRanking> aggregatedRankings;
    // 总回答数（成功的TaskResult数量）
    private Integer totalAnswers;
    // 有效回答数（提取到了品牌排名的回答数，有些回答可能没提任何品牌）
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

    /**
     * 【单个问题的排名明细】
     *
     * 设计思路：
     * 对应1条TaskResult记录的排名分析结果
     * 比如：在豆包平台上问"推荐拍照手机" → 提取到的品牌排名列表
     */
    public static class QuestionRanking {
        // 对应TaskResult表的主键ID
        private Long resultId;
        // 对应TaskQuestion表的主键ID
        private Long questionId;
        // 问题文本
        private String questionText;
        // AI平台代码（如"doubao"）
        private String aiPlatform;
        // AI平台中文名（如"豆包"）
        private String aiDisplayName;
        // AI回答的完整原文
        private String answerText;
        // 这条回答里提取到的品牌排名列表（按推荐顺序排）
        private List<BrandRankItem> brandRankings;
        // 问题的排序顺序（用于前端展示排序）
        private Integer sortOrder;
        // 这条结果的状态（SUCCESS/FAILED等）
        private String status;
        // 完成时间
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

    /**
     * 【单条回答中的单个品牌排名项】
     *
     * 设计思路：
     * 比如AI回答里说"推荐华为、小米、苹果这三款"，
     * 那华为就是rank=1，小米rank=2，苹果rank=3。
     * 同时记录品牌在文本中第一次出现的位置（firstIndex）和匹配到的上下文（matchedText），
     * 方便前端高亮展示给用户看"AI是在哪句话里提到的"。
     */
    public static class BrandRankItem {
        // 品牌名称
        private String brandName;
        // 是否是我们的自主品牌（true=是我们自己的品牌，false=竞对）
        private Boolean isSelfBrand;
        // 在这条回答里的排名（1=第一个被推荐，2=第二个...）
        private Integer rank;
        // 品牌名在回答文本中第一次出现的字符位置（用于前端高亮定位）
        private Integer firstIndex;
        // 匹配到品牌名时的上下文（品牌名前后几个字，方便用户看语境）
        private String matchedText;

        /** 无参构造（MyBatis/Jackson需要） */
        public BrandRankItem() {}

        /**
         * 全参构造方法
         * 设计思路：方便在代码里快速构造对象，不用一个个setter
         */
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

    /**
     * 【品牌综合汇总排名】
     *
     * 设计思路：
     * 把所有有效回答里的排名数据做聚合统计，得出每个品牌的"综合表现"。
     * 比如100条回答里，华为被提到80次，其中40次排第1，60次进前3，
     * 这些数据能帮我们判断"在AI眼里，这个品牌的口碑到底怎么样"。
     *
     * 几个关键指标的含义：
     * - mentionCount（提及次数）：品牌被AI提到的总次数
     * - firstCount（第一名次数）：排第1的次数，这个最重要
     * - top3Count（前三次数）：进入前三名的次数
     * - XxxRate（比率）：次数/有效回答数，消除"总回答数不一样"带来的偏差
     */
    public static class BrandAggregatedRanking {
        // 品牌名称
        private String brandName;
        // 是否是我们的自主品牌
        private Boolean isSelfBrand;
        // 被AI提到的总次数（有多少条回答里提到了这个品牌）
        private Integer mentionCount;
        // 排第1名的次数（有多少条回答里这个品牌是第一个被推荐的）
        private Integer firstCount;
        // 进入前3名的次数
        private Integer top3Count;
        // 进入前5名的次数
        private Integer top5Count;
        // 提及率 = mentionCount / validAnswers（0-1之间的小数）
        private Double mentionRate;
        // 第一名率 = firstCount / validAnswers
        private Double firstRate;
        // 前三率 = top3Count / validAnswers
        private Double top3Rate;
        // 前五率 = top5Count / validAnswers
        private Double top5Rate;
        // 排名分布：{排名1: 出现次数, 排名2: 出现次数...}，用于画排名分布柱状图
        private Map<Integer, Integer> rankDistribution;
        // 所有出现过的排名列表（原始数据，用于后续计算中位数等统计量）
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