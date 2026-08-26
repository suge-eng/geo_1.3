package com.geo.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public class AnalysisReportResponse {

    private String taskNo;
    private String title;
    private String brandName;
    private String productName;
    String periodLabel;
    private LocalDateTime reportDate;
    private String status;
    private Integer totalQuestions;
    private Integer totalPlatforms;
    private Integer totalResults;
    private Integer completedResults;

    private MetricGroup topMetrics;
    private MetricGroup productMetrics;
    private Integer overallScore;
    private String overallScoreSub;
    private List<MetricGroup> sideMetrics;
    private List<ChartWidget> charts;
    private List<RankingWidget> rankings;
    private AiSummary aiSummary;
    private BrandComparisonTable brandComparison;
    private ExposureMetrics exposureMetrics;
    private Map<String, List<String>> resultBrandRankings;
    private KeywordCloud keywordCloud;

    public KeywordCloud getKeywordCloud() { return keywordCloud; }
    public void setKeywordCloud(KeywordCloud keywordCloud) { this.keywordCloud = keywordCloud; }

    public String getTaskNo() { return taskNo; }
    public void setTaskNo(String taskNo) { this.taskNo = taskNo; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getBrandName() { return brandName; }
    public void setBrandName(String brandName) { this.brandName = brandName; }
    public String getProductName() { return productName; }
    public void setProductName(String productName) { this.productName = productName; }
    public String getPeriodLabel() { return periodLabel; }
    public void setPeriodLabel(String periodLabel) { this.periodLabel = periodLabel; }
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    public LocalDateTime getReportDate() { return reportDate; }
    public void setReportDate(LocalDateTime reportDate) { this.reportDate = reportDate; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Integer getTotalQuestions() { return totalQuestions; }
    public void setTotalQuestions(Integer totalQuestions) { this.totalQuestions = totalQuestions; }
    public Integer getTotalPlatforms() { return totalPlatforms; }
    public void setTotalPlatforms(Integer totalPlatforms) { this.totalPlatforms = totalPlatforms; }
    public Integer getTotalResults() { return totalResults; }
    public void setTotalResults(Integer totalResults) { this.totalResults = totalResults; }
    public Integer getCompletedResults() { return completedResults; }
    public void setCompletedResults(Integer completedResults) { this.completedResults = completedResults; }
    public MetricGroup getTopMetrics() { return topMetrics; }
    public void setTopMetrics(MetricGroup topMetrics) { this.topMetrics = topMetrics; }
    public MetricGroup getProductMetrics() { return productMetrics; }
    public void setProductMetrics(MetricGroup productMetrics) { this.productMetrics = productMetrics; }
    public Integer getOverallScore() { return overallScore; }
    public void setOverallScore(Integer overallScore) { this.overallScore = overallScore; }
    public String getOverallScoreSub() { return overallScoreSub; }
    public void setOverallScoreSub(String overallScoreSub) { this.overallScoreSub = overallScoreSub; }
    public List<MetricGroup> getSideMetrics() { return sideMetrics; }
    public void setSideMetrics(List<MetricGroup> sideMetrics) { this.sideMetrics = sideMetrics; }
    public List<ChartWidget> getCharts() { return charts; }
    public void setCharts(List<ChartWidget> charts) { this.charts = charts; }
    public List<RankingWidget> getRankings() { return rankings; }
    public void setRankings(List<RankingWidget> rankings) { this.rankings = rankings; }
    public AiSummary getAiSummary() { return aiSummary; }
    public void setAiSummary(AiSummary aiSummary) { this.aiSummary = aiSummary; }
    public BrandComparisonTable getBrandComparison() { return brandComparison; }
    public void setBrandComparison(BrandComparisonTable brandComparison) { this.brandComparison = brandComparison; }
    public ExposureMetrics getExposureMetrics() { return exposureMetrics; }
    public void setExposureMetrics(ExposureMetrics exposureMetrics) { this.exposureMetrics = exposureMetrics; }
    public Map<String, List<String>> getResultBrandRankings() { return resultBrandRankings; }
    public void setResultBrandRankings(Map<String, List<String>> resultBrandRankings) { this.resultBrandRankings = resultBrandRankings; }
    private Map<String, BrandComparisonTable> perPlatformBrandComparison;
    public Map<String, BrandComparisonTable> getPerPlatformBrandComparison() { return perPlatformBrandComparison; }
    public void setPerPlatformBrandComparison(Map<String, BrandComparisonTable> perPlatformBrandComparison) { this.perPlatformBrandComparison = perPlatformBrandComparison; }
    private List<CompetitionRankingItem> competitionRanking;
    public List<CompetitionRankingItem> getCompetitionRanking() { return competitionRanking; }
    public void setCompetitionRanking(List<CompetitionRankingItem> competitionRanking) { this.competitionRanking = competitionRanking; }

    private Boolean isGlobalReport;
    public Boolean getIsGlobalReport() { return isGlobalReport; }
    public void setIsGlobalReport(Boolean isGlobalReport) { this.isGlobalReport = isGlobalReport; }

    private Integer historyReportCount;
    public Integer getHistoryReportCount() { return historyReportCount; }
    public void setHistoryReportCount(Integer historyReportCount) { this.historyReportCount = historyReportCount; }

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime rangeStart;
    public LocalDateTime getRangeStart() { return rangeStart; }
    public void setRangeStart(LocalDateTime rangeStart) { this.rangeStart = rangeStart; }

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime rangeEnd;
    public LocalDateTime getRangeEnd() { return rangeEnd; }
    public void setRangeEnd(LocalDateTime rangeEnd) { this.rangeEnd = rangeEnd; }

    private Integer scoreChange;
    public Integer getScoreChange() { return scoreChange; }
    public void setScoreChange(Integer scoreChange) { this.scoreChange = scoreChange; }

    private String scoreLevel;
    public String getScoreLevel() { return scoreLevel; }
    public void setScoreLevel(String scoreLevel) { this.scoreLevel = scoreLevel; }

    private GlobalTrends globalTrends;
    public GlobalTrends getGlobalTrends() { return globalTrends; }
    public void setGlobalTrends(GlobalTrends globalTrends) { this.globalTrends = globalTrends; }

    private List<CitationPlatformRankingItem> citationPlatformRanking;
    public List<CitationPlatformRankingItem> getCitationPlatformRanking() { return citationPlatformRanking; }
    public void setCitationPlatformRanking(List<CitationPlatformRankingItem> citationPlatformRanking) { this.citationPlatformRanking = citationPlatformRanking; }

    private List<CitationSourceDistributionItem> citationSourceDistribution;
    public List<CitationSourceDistributionItem> getCitationSourceDistribution() { return citationSourceDistribution; }
    public void setCitationSourceDistribution(List<CitationSourceDistributionItem> citationSourceDistribution) { this.citationSourceDistribution = citationSourceDistribution; }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private final AnalysisReportResponse resp = new AnalysisReportResponse();
        public Builder taskNo(String taskNo) { resp.taskNo = taskNo; return this; }
        public Builder title(String title) { resp.title = title; return this; }
        public Builder brandName(String brandName) { resp.brandName = brandName; return this; }
        public Builder productName(String productName) { resp.productName = productName; return this; }
        public Builder periodLabel(String periodLabel) { resp.periodLabel = periodLabel; return this; }
        public Builder reportDate(LocalDateTime reportDate) { resp.reportDate = reportDate; return this; }
        public Builder status(String status) { resp.status = status; return this; }
        public Builder totalQuestions(Integer totalQuestions) { resp.totalQuestions = totalQuestions; return this; }
        public Builder totalPlatforms(Integer totalPlatforms) { resp.totalPlatforms = totalPlatforms; return this; }
        public Builder totalResults(Integer totalResults) { resp.totalResults = totalResults; return this; }
        public Builder completedResults(Integer completedResults) { resp.completedResults = completedResults; return this; }
        public Builder topMetrics(MetricGroup topMetrics) { resp.topMetrics = topMetrics; return this; }
        public Builder productMetrics(MetricGroup productMetrics) { resp.productMetrics = productMetrics; return this; }
        public Builder overallScore(Integer overallScore) { resp.overallScore = overallScore; return this; }
        public Builder overallScoreSub(String sub) { resp.overallScoreSub = sub; return this; }
        public Builder sideMetrics(List<MetricGroup> sideMetrics) { resp.sideMetrics = sideMetrics; return this; }
        public Builder charts(List<ChartWidget> charts) { resp.charts = charts; return this; }
        public Builder rankings(List<RankingWidget> rankings) { resp.rankings = rankings; return this; }
        public Builder aiSummary(AiSummary aiSummary) { resp.aiSummary = aiSummary; return this; }
        public Builder brandComparison(BrandComparisonTable brandComparison) { resp.brandComparison = brandComparison; return this; }
        public Builder exposureMetrics(ExposureMetrics exposureMetrics) { resp.exposureMetrics = exposureMetrics; return this; }
        public Builder resultBrandRankings(Map<String, List<String>> resultBrandRankings) { resp.resultBrandRankings = resultBrandRankings; return this; }
        public Builder perPlatformBrandComparison(Map<String, BrandComparisonTable> perPlatformBrandComparison) { resp.perPlatformBrandComparison = perPlatformBrandComparison; return this; }
        public Builder competitionRanking(List<CompetitionRankingItem> competitionRanking) { resp.competitionRanking = competitionRanking; return this; }
        public Builder keywordCloud(KeywordCloud keywordCloud) { resp.keywordCloud = keywordCloud; return this; }
        public Builder isGlobalReport(Boolean isGlobalReport) { resp.isGlobalReport = isGlobalReport; return this; }
        public Builder historyReportCount(Integer historyReportCount) { resp.historyReportCount = historyReportCount; return this; }
        public Builder rangeStart(LocalDateTime rangeStart) { resp.rangeStart = rangeStart; return this; }
        public Builder rangeEnd(LocalDateTime rangeEnd) { resp.rangeEnd = rangeEnd; return this; }
        public Builder scoreChange(Integer scoreChange) { resp.scoreChange = scoreChange; return this; }
        public Builder scoreLevel(String scoreLevel) { resp.scoreLevel = scoreLevel; return this; }
        public Builder globalTrends(GlobalTrends globalTrends) { resp.globalTrends = globalTrends; return this; }
        public Builder citationPlatformRanking(List<CitationPlatformRankingItem> citationPlatformRanking) { resp.citationPlatformRanking = citationPlatformRanking; return this; }
        public Builder citationSourceDistribution(List<CitationSourceDistributionItem> citationSourceDistribution) { resp.citationSourceDistribution = citationSourceDistribution; return this; }
        public Builder platformScoreCards(List<PlatformScoreCard> platformScoreCards) { resp.platformScoreCards = platformScoreCards; return this; }
        public AnalysisReportResponse build() { return resp; }
    }

    public static class MetricGroup {
        private String title;
        private String value;
        private String subValue;
        private String unit;
        private String trend;
        private String trendDirection;
        private List<MetricSubItem> subItems;
        private Map<String, String> extra;

        public String getTitle() { return title; }
        public void setTitle(String title) { this.title = title; }
        public String getValue() { return value; }
        public void setValue(String value) { this.value = value; }
        public String getSubValue() { return subValue; }
        public void setSubValue(String subValue) { this.subValue = subValue; }
        public String getUnit() { return unit; }
        public void setUnit(String unit) { this.unit = unit; }
        public String getTrend() { return trend; }
        public void setTrend(String trend) { this.trend = trend; }
        public String getTrendDirection() { return trendDirection; }
        public void setTrendDirection(String trendDirection) { this.trendDirection = trendDirection; }
        public List<MetricSubItem> getSubItems() { return subItems; }
        public void setSubItems(List<MetricSubItem> subItems) { this.subItems = subItems; }
        public Map<String, String> getExtra() { return extra; }
        public void setExtra(Map<String, String> extra) { this.extra = extra; }
    }

    public static class MetricSubItem {
        private String label;
        private String value;
        private String percentage;

        public String getLabel() { return label; }
        public void setLabel(String label) { this.label = label; }
        public String getValue() { return value; }
        public void setValue(String value) { this.value = value; }
        public String getPercentage() { return percentage; }
        public void setPercentage(String percentage) { this.percentage = percentage; }
    }

    public static class ChartWidget {
        private String id;
        private String title;
        private String type;
        private String yAxisLabel;
        private List<String> xAxis;
        private List<ChartSeries> series;
        private Map<String, String> extraConfig;

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getTitle() { return title; }
        public void setTitle(String title) { this.title = title; }
        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
        public String getYAxisLabel() { return yAxisLabel; }
        public void setYAxisLabel(String yAxisLabel) { this.yAxisLabel = yAxisLabel; }
        public List<String> getXAxis() { return xAxis; }
        public void setXAxis(List<String> xAxis) { this.xAxis = xAxis; }
        public List<ChartSeries> getSeries() { return series; }
        public void setSeries(List<ChartSeries> series) { this.series = series; }
        public Map<String, String> getExtraConfig() { return extraConfig; }
        public void setExtraConfig(Map<String, String> extraConfig) { this.extraConfig = extraConfig; }
    }

    public static class ChartSeries {
        private String name;
        private String color;
        private List<Double> data;

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getColor() { return color; }
        public void setColor(String color) { this.color = color; }
        public List<Double> getData() { return data; }
        public void setData(List<Double> data) { this.data = data; }
    }

    public static class RankingWidget {
        private String id;
        private String title;
        private List<String> headers;
        private List<RankingItem> items;

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getTitle() { return title; }
        public void setTitle(String title) { this.title = title; }
        public List<String> getHeaders() { return headers; }
        public void setHeaders(List<String> headers) { this.headers = headers; }
        public List<RankingItem> getItems() { return items; }
        public void setItems(List<RankingItem> items) { this.items = items; }
    }

    public static class RankingItem {
        private Integer rank;
        private String name;
        private String value;
        private String trend;
        private Map<String, String> columns;

        public Integer getRank() { return rank; }
        public void setRank(Integer rank) { this.rank = rank; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getValue() { return value; }
        public void setValue(String value) { this.value = value; }
        public String getTrend() { return trend; }
        public void setTrend(String trend) { this.trend = trend; }
        public Map<String, String> getColumns() { return columns; }
        public void setColumns(Map<String, String> columns) { this.columns = columns; }
    }

    public static class AiSummary {
        private String content;
        private List<String> highlights;
        private List<String> suggestions;

        public String getContent() { return content; }
        public void setContent(String content) { this.content = content; }
        public List<String> getHighlights() { return highlights; }
        public void setHighlights(List<String> highlights) { this.highlights = highlights; }
        public List<String> getSuggestions() { return suggestions; }
        public void setSuggestions(List<String> suggestions) { this.suggestions = suggestions; }
    }

    public static class BrandComparisonTable {
        private String title;
        private List<String> columns;
        private List<ComparisonRow> rows;
        private int totalValidAnswers;

        public String getTitle() { return title; }
        public void setTitle(String title) { this.title = title; }
        public List<String> getColumns() { return columns; }
        public void setColumns(List<String> columns) { this.columns = columns; }
        public List<ComparisonRow> getRows() { return rows; }
        public void setRows(List<ComparisonRow> rows) { this.rows = rows; }
        public int getTotalValidAnswers() { return totalValidAnswers; }
        public void setTotalValidAnswers(int totalValidAnswers) { this.totalValidAnswers = totalValidAnswers; }
    }

    public static class ComparisonRow {
        private String metric;
        private List<String> values;
        private List<String> trends;

        public String getMetric() { return metric; }
        public void setMetric(String metric) { this.metric = metric; }
        public List<String> getValues() { return values; }
        public void setValues(List<String> values) { this.values = values; }
        public List<String> getTrends() { return trends; }
        public void setTrends(List<String> trends) { this.trends = trends; }
    }

    public static class ExposureMetrics {
        private String mentionCount;
        private double coverageRate;
        private double firstRate;
        private double top3Rate;
        private double top5Rate;
        private int naturalRecommendationScore;
        private String naturalRecommendationSub;
        private int competitiveScore;
        private String competitiveSub;
        private Map<String, Integer> perPlatformSelfMentionCount;
        private double positiveReputationRate;
        private String positiveReputationSub;

        public String getMentionCount() { return mentionCount; }
        public void setMentionCount(String mentionCount) { this.mentionCount = mentionCount; }
        public double getCoverageRate() { return coverageRate; }
        public void setCoverageRate(double coverageRate) { this.coverageRate = coverageRate; }
        public double getFirstRate() { return firstRate; }
        public void setFirstRate(double firstRate) { this.firstRate = firstRate; }
        public double getTop3Rate() { return top3Rate; }
        public void setTop3Rate(double top3Rate) { this.top3Rate = top3Rate; }
        public double getTop5Rate() { return top5Rate; }
        public void setTop5Rate(double top5Rate) { this.top5Rate = top5Rate; }
        public int getNaturalRecommendationScore() { return naturalRecommendationScore; }
        public void setNaturalRecommendationScore(int naturalRecommendationScore) { this.naturalRecommendationScore = naturalRecommendationScore; }
        public String getNaturalRecommendationSub() { return naturalRecommendationSub; }
        public void setNaturalRecommendationSub(String naturalRecommendationSub) { this.naturalRecommendationSub = naturalRecommendationSub; }
        public int getCompetitiveScore() { return competitiveScore; }
        public void setCompetitiveScore(int competitiveScore) { this.competitiveScore = competitiveScore; }
        public String getCompetitiveSub() { return competitiveSub; }
        public void setCompetitiveSub(String competitiveSub) { this.competitiveSub = competitiveSub; }
        public Map<String, Integer> getPerPlatformSelfMentionCount() { return perPlatformSelfMentionCount; }
        public void setPerPlatformSelfMentionCount(Map<String, Integer> perPlatformSelfMentionCount) { this.perPlatformSelfMentionCount = perPlatformSelfMentionCount; }
        public double getPositiveReputationRate() { return positiveReputationRate; }
        public void setPositiveReputationRate(double positiveReputationRate) { this.positiveReputationRate = positiveReputationRate; }
        public String getPositiveReputationSub() { return positiveReputationSub; }
        public void setPositiveReputationSub(String positiveReputationSub) { this.positiveReputationSub = positiveReputationSub; }

        public static Builder builder() { return new Builder(); }

        public static class Builder {
            private final ExposureMetrics metrics = new ExposureMetrics();
            public Builder mentionCount(String mentionCount) { metrics.mentionCount = mentionCount; return this; }
            public Builder coverageRate(double coverageRate) { metrics.coverageRate = coverageRate; return this; }
            public Builder firstRate(double firstRate) { metrics.firstRate = firstRate; return this; }
            public Builder top3Rate(double top3Rate) { metrics.top3Rate = top3Rate; return this; }
            public Builder top5Rate(double top5Rate) { metrics.top5Rate = top5Rate; return this; }
            public Builder naturalRecommendationScore(int score) { metrics.naturalRecommendationScore = score; return this; }
            public Builder naturalRecommendationSub(String sub) { metrics.naturalRecommendationSub = sub; return this; }
            public Builder competitiveScore(int score) { metrics.competitiveScore = score; return this; }
            public Builder competitiveSub(String sub) { metrics.competitiveSub = sub; return this; }
            public Builder perPlatformSelfMentionCount(Map<String, Integer> map) { metrics.perPlatformSelfMentionCount = map; return this; }
            public Builder positiveReputationRate(double rate) { metrics.positiveReputationRate = rate; return this; }
            public Builder positiveReputationSub(String sub) { metrics.positiveReputationSub = sub; return this; }
            public ExposureMetrics build() { return metrics; }
        }
    }

    public static class CompetitionRankingItem {
        private int rank;
        private String brandName;
        private boolean isSelfBrand;
        private String score;
        private String trend;

        public int getRank() { return rank; }
        public void setRank(int rank) { this.rank = rank; }
        public String getBrandName() { return brandName; }
        public void setBrandName(String brandName) { this.brandName = brandName; }
        public boolean isSelfBrand() { return isSelfBrand; }
        public void setSelfBrand(boolean selfBrand) { isSelfBrand = selfBrand; }
        public String getScore() { return score; }
        public void setScore(String score) { this.score = score; }
        public String getTrend() { return trend; }
        public void setTrend(String trend) { this.trend = trend; }
    }

    public static class KeywordCloud {
        private List<KeywordItem> positive;
        private List<KeywordItem> negative;

        public List<KeywordItem> getPositive() { return positive; }
        public void setPositive(List<KeywordItem> positive) { this.positive = positive; }
        public List<KeywordItem> getNegative() { return negative; }
        public void setNegative(List<KeywordItem> negative) { this.negative = negative; }
    }

    public static class KeywordItem {
        private String text;
        private Integer count;

        public KeywordItem() {}

        public KeywordItem(String text, Integer count) {
            this.text = text;
            this.count = count;
        }

        public String getText() { return text; }
        public void setText(String text) { this.text = text; }
        public Integer getCount() { return count; }
        public void setCount(Integer count) { this.count = count; }
    }

    public static class GlobalTrends {
        private List<String> dates;
        private TrendMetricGroup brandVoice;
        private TrendMetricGroup citationHeat;
        private TrendMetricGroup positiveSentiment;
        private TrendMetricGroup positioningFit;

        public List<String> getDates() { return dates; }
        public void setDates(List<String> dates) { this.dates = dates; }
        public TrendMetricGroup getBrandVoice() { return brandVoice; }
        public void setBrandVoice(TrendMetricGroup brandVoice) { this.brandVoice = brandVoice; }
        public TrendMetricGroup getCitationHeat() { return citationHeat; }
        public void setCitationHeat(TrendMetricGroup citationHeat) { this.citationHeat = citationHeat; }
        public TrendMetricGroup getPositiveSentiment() { return positiveSentiment; }
        public void setPositiveSentiment(TrendMetricGroup positiveSentiment) { this.positiveSentiment = positiveSentiment; }
        public TrendMetricGroup getPositioningFit() { return positioningFit; }
        public void setPositioningFit(TrendMetricGroup positioningFit) { this.positioningFit = positioningFit; }
    }

    public static class TrendMetricGroup {
        private String unit;
        private List<TrendSeries> series;

        public String getUnit() { return unit; }
        public void setUnit(String unit) { this.unit = unit; }
        public List<TrendSeries> getSeries() { return series; }
        public void setSeries(List<TrendSeries> series) { this.series = series; }
    }

    public static class TrendSeries {
        private String name;
        private String color;
        private List<Double> data;

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getColor() { return color; }
        public void setColor(String color) { this.color = color; }
        public List<Double> getData() { return data; }
        public void setData(List<Double> data) { this.data = data; }
    }

    public static class CitationPlatformRankingItem {
        private int rank;
        private String sourceAddress;
        private int citationCount;
        private String trend;

        public int getRank() { return rank; }
        public void setRank(int rank) { this.rank = rank; }
        public String getSourceAddress() { return sourceAddress; }
        public void setSourceAddress(String sourceAddress) { this.sourceAddress = sourceAddress; }
        public int getCitationCount() { return citationCount; }
        public void setCitationCount(int citationCount) { this.citationCount = citationCount; }
        public String getTrend() { return trend; }
        public void setTrend(String trend) { this.trend = trend; }
    }

    public static class CitationSourceDistributionItem {
        private String url;
        private int citationCount;

        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }
        public int getCitationCount() { return citationCount; }
        public void setCitationCount(int citationCount) { this.citationCount = citationCount; }
    }

    public static class PlatformScoreCard {
        private String platformCode;
        private String platformName;
        private Integer rank;
        private Double score;
        private String audienceScale;
        private String coreFeatures;
        private String sourcePreference;
        private String optimizationAdvice;
        private String brandPerformance;

        public String getPlatformCode() { return platformCode; }
        public void setPlatformCode(String platformCode) { this.platformCode = platformCode; }
        public String getPlatformName() { return platformName; }
        public void setPlatformName(String platformName) { this.platformName = platformName; }
        public Integer getRank() { return rank; }
        public void setRank(Integer rank) { this.rank = rank; }
        public Double getScore() { return score; }
        public void setScore(Double score) { this.score = score; }
        public String getAudienceScale() { return audienceScale; }
        public void setAudienceScale(String audienceScale) { this.audienceScale = audienceScale; }
        public String getCoreFeatures() { return coreFeatures; }
        public void setCoreFeatures(String coreFeatures) { this.coreFeatures = coreFeatures; }
        public String getSourcePreference() { return sourcePreference; }
        public void setSourcePreference(String sourcePreference) { this.sourcePreference = sourcePreference; }
        public String getOptimizationAdvice() { return optimizationAdvice; }
        public void setOptimizationAdvice(String optimizationAdvice) { this.optimizationAdvice = optimizationAdvice; }
        public String getBrandPerformance() { return brandPerformance; }
        public void setBrandPerformance(String brandPerformance) { this.brandPerformance = brandPerformance; }
    }

    private List<PlatformScoreCard> platformScoreCards;
    public List<PlatformScoreCard> getPlatformScoreCards() { return platformScoreCards; }
    public void setPlatformScoreCards(List<PlatformScoreCard> platformScoreCards) { this.platformScoreCards = platformScoreCards; }
}
