package com.geo.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 【完整分析报告响应VO】
 *
 * 设计思路：
 * 1. 这是整个系统"最终交付物"的数据结构 - 一份完整的AI品牌分析报告。
 *    所有TaskResult数据收集完成后，AnalysisService会把原始数据加工成这个结构化报告。
 *
 * 2. 为什么结构这么复杂？
 *    一份专业的市场调研报告包含很多模块：核心指标、图表、排名、AI总结、品牌对比表等。
 *    所以用了很多内部类来模块化组织，每个内部类对应报告的一个"积木块"。
 *
 * 3. @JsonIgnoreProperties(ignoreUnknown=true) 的作用：
 *    前端传JSON时如果有多余的字段，Jackson不会报错直接忽略，
 *    避免前后端版本不一致时接口直接崩掉（兼容性设计）。
 *
 * 4. 报告各模块一览：
 *    - topMetrics/productMetrics：核心指标卡片（最上面几个大数字）
 *    - overallScore：品牌综合评分（0-100分）
 *    - charts：各种图表数据（柱状图、折线图等，给ECharts用）
 *    - rankings：各种排名榜
 *    - aiSummary：AI生成的文字总结和建议
 *    - brandComparison：品牌横向对比表格
 *    - exposureMetrics：曝光率和推荐度指标
 *    - keywordCloud：正面/负面关键词云
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class AnalysisReportResponse {

    // ======== 任务基础信息 ========
    /** 任务编号 */
    private String taskNo;
    /** 任务标题 */
    private String title;
    /** 自主品牌名称 */
    private String brandName;
    /** 单品名称（可选） */
    private String productName;
    /** 周期标签文字，如"2024-01-15 日报" / "2024年第3周 周报" */
    String periodLabel;
    /** 报告生成日期 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime reportDate;
    /** 报告状态（一般是COMPLETED） */
    private String status;

    // ======== 数据量统计 ========
    /** 问题总数 */
    private Integer totalQuestions;
    /** AI平台数 */
    private Integer totalPlatforms;
    /** 预期总结果数（= 平台数 × 问题数） */
    private Integer totalResults;
    /** 实际完成结果数 */
    private Integer completedResults;

    // ======== 报告核心内容模块 ========
    /** 顶部核心指标组（比如"品牌提及率"、"综合推荐率"等几个大卡片） */
    private MetricGroup topMetrics;
    /** 产品相关指标组 */
    private MetricGroup productMetrics;
    /** 品牌综合评分（0-100分，满分100） */
    private Integer overallScore;
    /** 综合评分说明文字（比如"处于行业上游水平"） */
    private String overallScoreSub;
    /** 侧边指标组列表（多个辅助指标） */
    private List<MetricGroup> sideMetrics;
    /** 图表组件列表（每个元素对应ECharts的一张图） */
    private List<ChartWidget> charts;
    /** 排名组件列表（各种排行榜） */
    private List<RankingWidget> rankings;
    /** AI生成的文字总结和建议 */
    private AiSummary aiSummary;
    /** 品牌横向对比表格（自主品牌 vs 各竞对） */
    private BrandComparisonTable brandComparison;
    /** 曝光/推荐相关指标 */
    private ExposureMetrics exposureMetrics;
    /** 每条结果的品牌排名原始数据（key=resultId，value=品牌列表） */
    private Map<String, List<String>> resultBrandRankings;
    /** 每个AI平台的情感分析结果（key=平台code，value=positive/negative/neutral） */
    private Map<String, String> aiSentimentMap;
    /** 关键词云数据（正面词+负面词） */
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
    public Map<String, String> getAiSentimentMap() { return aiSentimentMap; }
    public void setAiSentimentMap(Map<String, String> aiSentimentMap) { this.aiSentimentMap = aiSentimentMap; }
    /** 每个AI平台单独的品牌对比表（key=平台code，value=该平台专属的对比数据） */
    private Map<String, BrandComparisonTable> perPlatformBrandComparison;
    public Map<String, BrandComparisonTable> getPerPlatformBrandComparison() { return perPlatformBrandComparison; }
    public void setPerPlatformBrandComparison(Map<String, BrandComparisonTable> perPlatformBrandComparison) { this.perPlatformBrandComparison = perPlatformBrandComparison; }
    /** 综合竞争力排行榜（所有品牌按综合得分排名） */
    private List<CompetitionRankingItem> competitionRanking;
    public List<CompetitionRankingItem> getCompetitionRanking() { return competitionRanking; }
    public void setCompetitionRanking(List<CompetitionRankingItem> competitionRanking) { this.competitionRanking = competitionRanking; }

    /** 是否是全局报告（true=跨多个任务合并的全局分析，false=单个任务的报告） */
    private Boolean isGlobalReport;
    public Boolean getIsGlobalReport() { return isGlobalReport; }
    public void setIsGlobalReport(Boolean isGlobalReport) { this.isGlobalReport = isGlobalReport; }

    /** 历史报告数量（这个任务之前生成过几份报告，用于周期任务趋势分析） */
    private Integer historyReportCount;
    public Integer getHistoryReportCount() { return historyReportCount; }
    public void setHistoryReportCount(Integer historyReportCount) { this.historyReportCount = historyReportCount; }

    /** 报告数据统计范围开始时间（全局报告/周期报告用） */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime rangeStart;
    public LocalDateTime getRangeStart() { return rangeStart; }
    public void setRangeStart(LocalDateTime rangeStart) { this.rangeStart = rangeStart; }

    /** 报告数据统计范围结束时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime rangeEnd;
    public LocalDateTime getRangeEnd() { return rangeEnd; }
    public void setRangeEnd(LocalDateTime rangeEnd) { this.rangeEnd = rangeEnd; }

    /** 分数较上次变化值（正数=上升，负数=下降） */
    private Integer scoreChange;
    public Integer getScoreChange() { return scoreChange; }
    public void setScoreChange(Integer scoreChange) { this.scoreChange = scoreChange; }

    /** 分数等级（如"优秀"/"良好"/"一般"，前端根据分数显示不同颜色） */
    private String scoreLevel;
    public String getScoreLevel() { return scoreLevel; }
    public void setScoreLevel(String scoreLevel) { this.scoreLevel = scoreLevel; }

    /** 历史趋势数据（折线图数据，品牌声量/引用热度等随时间变化） */
    private GlobalTrends globalTrends;
    public GlobalTrends getGlobalTrends() { return globalTrends; }
    public void setGlobalTrends(GlobalTrends globalTrends) { this.globalTrends = globalTrends; }

    /** AI引用来源平台排行榜（哪个平台的链接被AI引用最多） */
    private List<CitationPlatformRankingItem> citationPlatformRanking;
    public List<CitationPlatformRankingItem> getCitationPlatformRanking() { return citationPlatformRanking; }
    public void setCitationPlatformRanking(List<CitationPlatformRankingItem> citationPlatformRanking) { this.citationPlatformRanking = citationPlatformRanking; }

    /** AI引用来源具体URL分布统计（哪篇文章被引用次数最多） */
    private List<CitationSourceDistributionItem> citationSourceDistribution;
    public List<CitationSourceDistributionItem> getCitationSourceDistribution() { return citationSourceDistribution; }
    public void setCitationSourceDistribution(List<CitationSourceDistributionItem> citationSourceDistribution) { this.citationSourceDistribution = citationSourceDistribution; }
    public static Builder builder() { return new Builder(); }

    /**
     * 【报告建造者】
     * 设计思路：报告字段实在太多了（30+个），用Builder链式调用让构造代码清晰可读
     */
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
        public Builder aiSentimentMap(Map<String, String> aiSentimentMap) { resp.aiSentimentMap = aiSentimentMap; return this; }
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

    /**
     * 【指标组组件】
     *
     * 设计思路：
     * 报告里最常见的展示单元就是"一个大数字+说明文字"的卡片，
     * 比如"品牌提及率 78% - 较上周+5%"。
     * 这个类就是通用的指标卡片模型，一套结构适配各种指标场景。
     * 还支持subItems子项（一个大指标下包含几个小分指标）。
     */
    public static class MetricGroup {
        /** 指标标题，如"品牌提及率" */
        private String title;
        /** 主数值（大字体显示的那个，如"78%"） */
        private String value;
        /** 数值副标题/补充说明 */
        private String subValue;
        /** 数值单位，如"%"、"次"、"分" */
        private String unit;
        /** 趋势文字，如"+5%"、"-3.2%" */
        private String trend;
        /** 趋势方向：up=上升（绿色）/down=下降（红色）/flat=持平（灰色） */
        private String trendDirection;
        /** 子指标列表（这个大指标下面的几个细分指标） */
        private List<MetricSubItem> subItems;
        /** 额外扩展字段（预留，放一些特殊配置） */
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

    /**
     * 【子指标项】
     * 设计思路：MetricGroup的子项，比如"正面评价率"大指标下分"豆包 85%"、"Kimi 78%"等小项
     */
    public static class MetricSubItem {
        /** 子项标签，如"豆包" */
        private String label;
        /** 子项数值，如"85%" */
        private String value;
        /** 子项占比（可选），如"78%" */
        private String percentage;

        public String getLabel() { return label; }
        public void setLabel(String label) { this.label = label; }
        public String getValue() { return value; }
        public void setValue(String value) { this.value = value; }
        public String getPercentage() { return percentage; }
        public void setPercentage(String percentage) { this.percentage = percentage; }
    }

    /**
     * 【图表组件】
     *
     * 设计思路：
     * 这是给前端ECharts图表库用的通用数据结构。
     * 一套结构适配多种图表类型（柱状图、折线图、饼图等），type字段决定用哪种图渲染。
     * xAxis + series 是ECharts的标准配置格式，前端拿到基本不用改就能直接渲染。
     */
    public static class ChartWidget {
        /** 图表唯一ID（前端用） */
        private String id;
        /** 图表标题 */
        private String title;
        /** 图表类型：bar=柱状图，line=折线图，pie=饼图，radar=雷达图 */
        private String type;
        /** Y轴标签文字 */
        private String yAxisLabel;
        /** X轴数据（横轴的分类标签，如各品牌名/各日期） */
        private List<String> xAxis;
        /** 数据系列（一个图表可以有多条线/多组柱子） */
        private List<ChartSeries> series;
        /** 额外的ECharts配置（预留特殊配置） */
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

    /**
     * 【图表数据系列】
     * 设计思路：ECharts里一条折线/一组柱子就是一个series。
     * name是图例名，color是这组数据的颜色，data是具体的数值数组。
     */
    public static class ChartSeries {
        /** 系列名称（图例显示的名字），如"自主品牌"、"小米" */
        private String name;
        /** 系列显示颜色（16进制颜色值，如"#5470c6"） */
        private String color;
        /** 具体数值数组，跟xAxis一一对应 */
        private List<Double> data;

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getColor() { return color; }
        public void setColor(String color) { this.color = color; }
        public List<Double> getData() { return data; }
        public void setData(List<Double> data) { this.data = data; }
    }

    /**
     * 【排行榜组件】
     * 设计思路：通用的排行榜数据结构，支持"标题+表头+多行数据"的格式。
     * 可以用来展示品牌推荐榜、引用来源榜、AI平台评分榜等各种榜单。
     */
    public static class RankingWidget {
        /** 排行榜唯一ID */
        private String id;
        /** 排行榜标题，如"品牌综合推荐榜" */
        private String title;
        /** 表头列名数组，如["排名","品牌","得分","趋势"] */
        private List<String> headers;
        /** 排行数据行 */
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

    /**
     * 【排行榜单条数据】
     * 设计思路：通用的排行数据行。rank+name+value是最基础的三列，
     * 不够用的话columns可以放任意多列的键值对（灵活性设计）。
     */
    public static class RankingItem {
        /** 排名数字（1=第1名，2=第2名...） */
        private Integer rank;
        /** 主体名称，如品牌名、平台名 */
        private String name;
        /** 主数值，如得分、次数等 */
        private String value;
        /** 趋势描述，如"↑上升"、"↓下降" */
        private String trend;
        /** 其他扩展列（key=列名，value=单元格值），适配多列表格 */
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

    /**
     * 【AI生成的总结报告】
     *
     * 设计思路：
     * 所有数据分析完后，可以调用大模型（或规则模板）生成一段"人话总结"，
     * 让用户不用看一堆数字，直接看结论就行。
     * 分成三部分：一段总览文字 + 几个亮点要点 + 几条行动建议。
     */
    public static class AiSummary {
        /** 总结正文（一段话，可读的自然语言总结） */
        private String content;
        /** 关键亮点列表（3-5个要点，比如"提及率排名第一"） */
        private List<String> highlights;
        /** 优化建议列表（3-5条可执行的建议） */
        private List<String> suggestions;

        public String getContent() { return content; }
        public void setContent(String content) { this.content = content; }
        public List<String> getHighlights() { return highlights; }
        public void setHighlights(List<String> highlights) { this.highlights = highlights; }
        public List<String> getSuggestions() { return suggestions; }
        public void setSuggestions(List<String> suggestions) { this.suggestions = suggestions; }
    }

    /**
     * 【品牌横向对比表格】
     *
     * 设计思路：
     * 报告最核心的部分之一 - 把自主品牌和每个竞争对手放在同一张表里横向对比。
     * 行=对比维度（提及率、第一名率、正面评价率等），
     * 列=各个品牌（第一列是自主品牌，后面是各竞对品牌）。
     * 这样用户一眼就能看出"我们跟竞品比谁强谁弱"。
     */
    public static class BrandComparisonTable {
        /** 表格标题 */
        private String title;
        /** 列名数组（第一列通常是"维度"，后面是各品牌名） */
        private List<String> columns;
        /** 数据行（每一行一个对比维度） */
        private List<ComparisonRow> rows;
        /** 统计时基于的有效回答总数（标注样本量，增加可信度） */
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

    /**
     * 【品牌对比表的一行数据】
     * 设计思路：一行对应一个对比维度。
     * metric是维度名（如"提及率"），values是各品牌在这个维度上的数值，
     * trends是各品牌较上次的趋势（可选）。
     */
    public static class ComparisonRow {
        /** 对比维度名称，如"提及率"、"第一名率" */
        private String metric;
        /** 各品牌数值（跟columns顺序一一对应） */
        private List<String> values;
        /** 各品牌趋势（可选，跟values对应，如"+3%"、"-1%"） */
        private List<String> trends;

        public String getMetric() { return metric; }
        public void setMetric(String metric) { this.metric = metric; }
        public List<String> getValues() { return values; }
        public void setValues(List<String> values) { this.values = values; }
        public List<String> getTrends() { return trends; }
        public void setTrends(List<String> trends) { this.trends = trends; }
    }

    /**
     * 【曝光/推荐相关核心指标】
     *
     * 设计思路：
     * 这组指标是报告最核心的KPI，直接回答"我们品牌在AI眼里表现怎么样"。
     * 包括两层数据：
     * 1. 汇总指标（给用户看的大数字）
     * 2. 分平台明细（每个AI平台单独的数据，方便定位"哪个平台对我们不友好"）
     *
     * 几个关键指标说明：
     * - coverageRate（覆盖率/提及率）：多少回答里提到了我们 → AI认不认我们
     * - firstRate（第一名率）：多少次排第一 → AI是不是最推荐我们
     * - naturalRecommendationScore（自然推荐分）：加权综合得分，越高越好
     * - positiveReputationRate（正面口碑率）：正面评价占比 → AI对我们的评价好坏
     */
    public static class ExposureMetrics {
        /** 品牌总提及次数（多少条回答里提到了我们） */
        private String mentionCount;
        /** 覆盖率/提及率 = 提及次数 / 有效回答数 */
        private double coverageRate;
        /** 第一名率 = 排第1的次数 / 有效回答数 */
        private double firstRate;
        /** 前三名率 */
        private double top3Rate;
        /** 前五名率 */
        private double top5Rate;
        /** 自然推荐综合评分（0-100分，综合各种权重的加权得分） */
        private int naturalRecommendationScore;
        /** 自然推荐分说明文字 */
        private String naturalRecommendationSub;
        /** 竞争力评分（自主品牌 vs 竞对的对比得分） */
        private int competitiveScore;
        /** 竞争力分说明文字 */
        private String competitiveSub;
        /** 每个平台的自主品牌提及次数（key=平台code，value=次数） */
        private Map<String, Integer> perPlatformSelfMentionCount;
        /** 正面口碑率 = 正面评价数 / (正面+负面) 总数 */
        private double positiveReputationRate;
        /** 正面口碑率说明 */
        private String positiveReputationSub;
        /** 每个平台的白名单来源匹配次数（统计官方/权威来源引用情况） */
        private Map<String, Integer> perPlatformWhitelistMatchCount;
        /** 每个平台的总引用来源数 */
        private Map<String, Integer> perPlatformTotalSourceCount;
        /** 每个平台的白名单引用匹配率 = 白名单匹配 / 总引用数 */
        private Map<String, Double> perPlatformWhitelistMatchRate;

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
        public Map<String, Integer> getPerPlatformWhitelistMatchCount() { return perPlatformWhitelistMatchCount; }
        public void setPerPlatformWhitelistMatchCount(Map<String, Integer> perPlatformWhitelistMatchCount) { this.perPlatformWhitelistMatchCount = perPlatformWhitelistMatchCount; }
        public Map<String, Integer> getPerPlatformTotalSourceCount() { return perPlatformTotalSourceCount; }
        public void setPerPlatformTotalSourceCount(Map<String, Integer> perPlatformTotalSourceCount) { this.perPlatformTotalSourceCount = perPlatformTotalSourceCount; }
        public Map<String, Double> getPerPlatformWhitelistMatchRate() { return perPlatformWhitelistMatchRate; }
        public void setPerPlatformWhitelistMatchRate(Map<String, Double> perPlatformWhitelistMatchRate) { this.perPlatformWhitelistMatchRate = perPlatformWhitelistMatchRate; }

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
            public Builder perPlatformWhitelistMatchCount(Map<String, Integer> map) { metrics.perPlatformWhitelistMatchCount = map; return this; }
            public Builder perPlatformTotalSourceCount(Map<String, Integer> map) { metrics.perPlatformTotalSourceCount = map; return this; }
            public Builder perPlatformWhitelistMatchRate(Map<String, Double> map) { metrics.perPlatformWhitelistMatchRate = map; return this; }
            public ExposureMetrics build() { return metrics; }
        }
    }

    /**
     * 【竞争力排行榜单项】
     * 设计思路：所有品牌（包括自主和竞对）放在一个榜单里按综合得分排名，
     * isSelfBrand标记哪个是我们自己，方便前端高亮显示。
     */
    public static class CompetitionRankingItem {
        /** 排名（1=第1） */
        private int rank;
        /** 品牌名称 */
        private String brandName;
        /** 是否是我们的自主品牌（true的话前端可以高亮显示） */
        private boolean isSelfBrand;
        /** 综合得分 */
        private String score;
        /** 较上次变化趋势 */
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

    /**
     * 【关键词云数据】
     * 设计思路：把AI回答里出现的高频正面词和负面词提取出来，
     * 前端用标签云/词云组件渲染，直观展示"AI提到我们时最常说什么词"。
     * 分positive和negative两组，对比展示。
     */
    public static class KeywordCloud {
        /** 高频正面关键词（如"优秀、推荐、领先"等） */
        private List<KeywordItem> positive;
        /** 高频负面关键词（如"不足、缺点、一般"等） */
        private List<KeywordItem> negative;

        public List<KeywordItem> getPositive() { return positive; }
        public void setPositive(List<KeywordItem> positive) { this.positive = positive; }
        public List<KeywordItem> getNegative() { return negative; }
        public void setNegative(List<KeywordItem> negative) { this.negative = negative; }
    }

    /**
     * 【关键词项】
     * 设计思路：一个词 + 出现次数。count越大，词云里字号越大。
     */
    public static class KeywordItem {
        /** 关键词文本 */
        private String text;
        /** 出现次数（决定词云中的字号大小） */
        private Integer count;

        /** 无参构造（Jackson需要） */
        public KeywordItem() {}

        /** 快速构造方法 */
        public KeywordItem(String text, Integer count) {
            this.text = text;
            this.count = count;
        }

        public String getText() { return text; }
        public void setText(String text) { this.text = text; }
        public Integer getCount() { return count; }
        public void setCount(Integer count) { this.count = count; }
    }

    /**
     * 【历史趋势数据组】
     *
     * 设计思路：
     * 周期任务（日报/周报）运行一段时间后，会有很多历史报告。
     * 把历史数据按时间串起来就是趋势图（折线图），可以看"我们品牌的声量是上升还是下降"。
     * 包含4个维度的趋势：品牌声量、引用热度、正面情感、定位匹配度。
     */
    public static class GlobalTrends {
        /** X轴日期数组（如["01-01", "01-02", "01-03"...]） */
        private List<String> dates;
        /** 品牌声量趋势（品牌被提到的次数变化） */
        private TrendMetricGroup brandVoice;
        /** 引用来源热度趋势（AI引用外部链接的次数变化） */
        private TrendMetricGroup citationHeat;
        /** 正面情感率趋势（评价变好还是变差） */
        private TrendMetricGroup positiveSentiment;
        /** 定位匹配度趋势（品牌定位和用户问题匹配度变化） */
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

    /**
     * 【趋势图指标组】
     * 设计思路：一个趋势图维度可以包含多条折线（比如"自主品牌"+"小米"+"苹果"三条对比），
     * 所以用series列表装多条线。unit是数值单位（如"%"，"次"）。
     */
    public static class TrendMetricGroup {
        /** 数值单位，如"%"、"次"、"分" */
        private String unit;
        /** 多条折线数据（比如每个品牌一条线） */
        private List<TrendSeries> series;

        public String getUnit() { return unit; }
        public void setUnit(String unit) { this.unit = unit; }
        public List<TrendSeries> getSeries() { return series; }
        public void setSeries(List<TrendSeries> series) { this.series = series; }
    }

    /**
     * 【趋势图折线数据】
     * 设计思路：跟ChartSeries类似，专门给历史趋势折线图用。
     * name是这条线代表的对象（如品牌名），color是线的颜色，data是每天的数值点。
     */
    public static class TrendSeries {
        /** 系列名称，如"小米"、"自主品牌" */
        private String name;
        /** 线条颜色（16进制，如"#5470c6"） */
        private String color;
        /** 数值点数组，跟dates数组一一对应 */
        private List<Double> data;

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getColor() { return color; }
        public void setColor(String color) { this.color = color; }
        public List<Double> getData() { return data; }
        public void setData(List<Double> data) { this.data = data; }
    }

    /**
     * 【AI引用来源平台排行榜项】
     *
     * 设计思路：
     * AI回答问题时会引用外部网页链接。统计"哪些网站被引用最多"，
     * 可以帮助SEO/内容运营同学知道"应该去哪些平台发文章"。
     * sourceAddress存的是域名或平台名（如zhihu.com、baidu.com）。
     */
    public static class CitationPlatformRankingItem {
        /** 排名 */
        private int rank;
        /** 来源平台/域名（如"知乎"、"什么值得买"） */
        private String sourceAddress;
        /** 被引用次数 */
        private int citationCount;
        /** 较上次趋势 */
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

    /**
     * 【具体引用URL分布项】
     *
     * 设计思路：
     * 比CitationPlatformRankingItem更细粒度——统计具体是哪篇文章/哪个URL被AI引用最多。
     * 这样内容运营同学可以直接定位到"哪篇爆款文章被AI反复引用"，然后继续加强。
     */
    public static class CitationSourceDistributionItem {
        /** 被引用的具体网页URL */
        private String url;
        /** 被AI引用的次数 */
        private int citationCount;

        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }
        public int getCitationCount() { return citationCount; }
        public void setCitationCount(int citationCount) { this.citationCount = citationCount; }
    }

    /**
     * 【AI平台评分卡片】
     *
     * 设计思路：
     * 报告中每个AI平台（豆包/Kimi/DeepSeek等）一张独立的卡片，
     * 展示这个平台的特点，以及"我们品牌在这个平台上的表现"。
     *
     * 核心信息：
     * 1. 平台基础档案（用户规模、偏好、特点）—— 帮用户理解这个平台的受众
     * 2. 品牌在该平台的得分和排名
     * 3. 针对这个平台的优化建议（比如"豆包偏好知乎来源，多发知乎文章"）
     */
    public static class PlatformScoreCard {
        /** 平台唯一编码，如"doubao"、"kimi" */
        private String platformCode;
        /** 平台显示名称，如"豆包"、"Kimi" */
        private String platformName;
        /** 本品牌在这个平台上的排名（跟其他品牌比） */
        private Integer rank;
        /** 本品牌在这个平台上的综合得分（0-100） */
        private Double score;
        /** 该平台用户规模描述，如"日活5000万+" */
        private String audienceScale;
        /** 平台核心特点描述，如"侧重搜索、引用权威来源" */
        private String coreFeatures;
        /** 该平台的引用来源偏好，如"偏爱知乎、B站" */
        private String sourcePreference;
        /** 针对这个平台的品牌优化建议（可执行的具体建议） */
        private String optimizationAdvice;
        /** 本品牌在该平台的综合表现总结文字 */
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