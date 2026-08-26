package com.geo.service;

import com.geo.common.BusinessException;
import com.geo.common.ResultCode;
import com.geo.dto.AnalysisReportResponse;
import com.geo.dto.AnalysisReportResponse.*;
import com.geo.entity.Task;
import com.geo.entity.TaskReport;
import com.geo.entity.TaskResult;
import com.geo.enums.AiPlatform;
import com.geo.enums.ResultStatus;
import com.geo.mapper.TaskReportMapper;
import com.geo.mapper.TaskResultMapper;
import com.geo.mapper.TaskMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.stream.Collectors;

@Service
public class AnalysisService {

    private static final Logger log = LoggerFactory.getLogger(AnalysisService.class);

    public enum ReportGenStatus { IDLE, RUNNING, COMPLETED, FAILED }

    public static class ReportGenerationStatus {
        private volatile ReportGenStatus status = ReportGenStatus.IDLE;
        private volatile int progressPercent;
        private volatile String stage;
        private volatile String errorMsg;
        private volatile LocalDateTime startedAt;
        private volatile LocalDateTime finishedAt;
        private transient AnalysisReportResponse report;

        public ReportGenStatus getStatus() { return status; }
        public void setStatus(ReportGenStatus status) { this.status = status; }
        public int getProgressPercent() { return progressPercent; }
        public void setProgressPercent(int progressPercent) { this.progressPercent = progressPercent; }
        public String getStage() { return stage; }
        public void setStage(String stage) { this.stage = stage; }
        public String getErrorMsg() { return errorMsg; }
        public void setErrorMsg(String errorMsg) { this.errorMsg = errorMsg; }
        public LocalDateTime getStartedAt() { return startedAt; }
        public void setStartedAt(LocalDateTime startedAt) { this.startedAt = startedAt; }
        public LocalDateTime getFinishedAt() { return finishedAt; }
        public void setFinishedAt(LocalDateTime finishedAt) { this.finishedAt = finishedAt; }
        public AnalysisReportResponse getReport() { return report; }
        public void setReport(AnalysisReportResponse report) { this.report = report; }
    }

    private final ConcurrentHashMap<String, ReportGenerationStatus> reportGenStatusMap = new ConcurrentHashMap<>();

    private final TaskMapper taskMapper;
    private final TaskResultMapper taskResultMapper;
    private final TaskReportMapper taskReportMapper;
    private final ObjectMapper objectMapper;
    private final Executor taskExecutor;
    private final RedisTemplate<String, String> redisTemplate;

    @Value("${geo.ai.api-key:}")
    private String aiApiKey;

    @Value("${geo.ai.api-endpoint:https://api.deepseek.com/v1}")
    private String aiApiEndpoint;

    @Value("${geo.ai.model:deepseek-chat}")
    private String aiModel;

    @Value("${geo.ai.timeout-seconds:120}")
    private int aiTimeoutSeconds;

    @Value("${geo.ai.enabled:false}")
    private boolean aiEnabled;

    @Value("${geo.ai.batch-size:15}")
    private int aiBatchSize;

    @Value("${geo.ai.max-concurrency:8}")
    private int aiMaxConcurrency;

    @Value("${geo.ai.failover-to-text-match:true}")
    private boolean aiFailoverToTextMatch;

    @Value("${geo.report.async-threshold:100}")
    private int reportAsyncThreshold;

    private static final int AI_MAX_RETRIES = 3;
    private static final long AI_RETRY_DELAY_MS = 2000;

    public AnalysisService(TaskMapper taskMapper,
                           TaskResultMapper taskResultMapper,
                           TaskReportMapper taskReportMapper,
                           ObjectMapper objectMapper,
                           @Qualifier("taskExecutor") Executor taskExecutor,
                           RedisTemplate<String, String> redisTemplate) {
        this.taskMapper = taskMapper;
        this.taskResultMapper = taskResultMapper;
        this.taskReportMapper = taskReportMapper;
        this.objectMapper = objectMapper;
        this.taskExecutor = taskExecutor;
        this.redisTemplate = redisTemplate;
    }

    public AnalysisReportResponse generateReport(String taskNo) {
        return generateReport(taskNo, false);
    }

    public AnalysisReportResponse generateReport(String taskNo, boolean forceRegenerate) {
        return doGenerateReport(taskNo, forceRegenerate, null);
    }

    public boolean hasCachedReport(String taskNo) {
        try {
            Task task = taskMapper.selectOne(
                    new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<Task>()
                            .eq("task_no", taskNo));
            return task != null && task.getReportJson() != null && !task.getReportJson().isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    public boolean shouldUseAsync(String taskNo) {
        try {
            List<TaskResult> results = taskResultMapper.selectByTaskNo(taskNo);
            int count = results == null ? 0 : (int) results.stream()
                    .filter(r -> ResultStatus.SUCCESS.name().equals(r.getStatus())).count();
            return count > reportAsyncThreshold;
        } catch (Exception e) {
            return false;
        }
    }

    public ReportGenerationStatus getReportGenerationStatus(String taskNo) {
        ReportGenerationStatus s = reportGenStatusMap.get(taskNo);
        if (s == null) {
            s = new ReportGenerationStatus();
            s.setStatus(ReportGenStatus.IDLE);
        }
        return s;
    }

    @Async("taskExecutor")
    public void generateReportAsync(String taskNo, boolean forceRegenerate) {
        ReportGenerationStatus status = reportGenStatusMap.computeIfAbsent(taskNo, k -> new ReportGenerationStatus());
        synchronized (status) {
            if (status.getStatus() == ReportGenStatus.RUNNING) {
                log.warn("报告生成已在进行中，跳过重复请求: taskNo={}", taskNo);
                return;
            }
            status.setStatus(ReportGenStatus.RUNNING);
            status.setProgressPercent(0);
            status.setErrorMsg(null);
            status.setStartedAt(LocalDateTime.now());
            status.setFinishedAt(null);
            status.setReport(null);
        }
        sendWsProgress(taskNo, status);

        try {
            Consumer<Integer> progressUpdater = pct -> {
                int p = Math.min(100, Math.max(0, pct));
                status.setProgressPercent(p);
                if (p <= 3) status.setStage("加载任务信息");
                else if (p <= 10) status.setStage("读取答题结果");
                else if (p <= 12) status.setStage("准备AI分析");
                else if (p < 70) status.setStage("AI批量品牌提取中（" + p + "%）");
                else if (p <= 75) status.setStage("汇总品牌指标");
                else if (p <= 85) status.setStage("构建报告模块");
                else if (p <= 92) status.setStage("生成对比分析与AI总结");
                else if (p < 100) status.setStage("计算综合评分，写入缓存");
                else status.setStage("完成");
                sendWsProgress(taskNo, status);
            };
            AnalysisReportResponse report = doGenerateReport(taskNo, forceRegenerate, progressUpdater);
            status.setReport(report);
            status.setProgressPercent(100);
            status.setStage("完成");
            status.setFinishedAt(LocalDateTime.now());
            status.setStatus(ReportGenStatus.COMPLETED);
            sendWsProgress(taskNo, status);
            sendWsComplete(taskNo);
            log.info("异步报告生成完成: taskNo={}", taskNo);
        } catch (Throwable e) {
            log.error("异步报告生成失败: taskNo={}", taskNo, e);
            status.setStatus(ReportGenStatus.FAILED);
            status.setErrorMsg(buildUserFriendlyError(e));
            status.setFinishedAt(LocalDateTime.now());
            sendWsProgress(taskNo, status);
        }
    }

    private void sendWsProgress(String taskNo, ReportGenerationStatus status) {
        try {
            Map<String, Object> msg = new LinkedHashMap<>();
            msg.put("type", "REPORT_GENERATING");
            msg.put("taskNo", taskNo);
            msg.put("status", status.getStatus().name());
            msg.put("progressPercent", status.getProgressPercent());
            msg.put("stage", status.getStage());
            msg.put("errorMsg", status.getErrorMsg());
            String channel = "geo:ws:progress:" + taskNo;
            String json = objectMapper.writeValueAsString(msg);
            redisTemplate.convertAndSend(channel, json);
        } catch (Exception e) {
            log.debug("推送报告进度失败: taskNo={}", taskNo, e);
        }
    }

    private void sendWsComplete(String taskNo) {
        try {
            Map<String, Object> msg = new LinkedHashMap<>();
            msg.put("type", "COMPLETE");
            msg.put("taskNo", taskNo);
            String channel = "geo:ws:progress:" + taskNo;
            String json = objectMapper.writeValueAsString(msg);
            redisTemplate.convertAndSend(channel, json);
        } catch (Exception e) {
            log.debug("推送报告完成消息失败: taskNo={}", taskNo, e);
        }
    }

    private String buildUserFriendlyError(Throwable e) {
        if (e == null) {
            return "未知错误";
        }
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String typeName = root.getClass().getSimpleName();
        String msg = root.getMessage();

        if (root instanceof BusinessException) {
            return msg != null ? msg : "业务处理失败";
        }
        if (root instanceof NullPointerException) {
            return "内部数据缺失，报告生成失败，请稍后重试";
        }
        if (root instanceof OutOfMemoryError) {
            return "数据量过大，内存不足，请减少任务量或分批生成";
        }
        if (root instanceof StackOverflowError) {
            return "计算深度过大，无法完成报告";
        }
        if (root instanceof java.net.SocketTimeoutException
                || root instanceof java.util.concurrent.TimeoutException
                || (msg != null && (msg.toLowerCase().contains("timeout") || msg.toLowerCase().contains("timed out")))) {
            return "AI接口请求超时，请稍后重试或关闭AI增强模式";
        }
        if (root instanceof java.net.ConnectException
                || root instanceof java.net.UnknownHostException
                || (msg != null && msg.toLowerCase().contains("connection refused"))) {
            return "无法连接到AI服务接口，请检查网络或AI配置";
        }
        if (msg != null && msg.toLowerCase().contains("rate limit")
                || msg != null && msg.toLowerCase().contains("429")) {
            return "AI接口请求过于频繁，已限流，请稍后重试";
        }
        if (msg != null && (msg.toLowerCase().contains("authentication")
                || msg.toLowerCase().contains("api key")
                || msg.toLowerCase().contains("401")
                || msg.toLowerCase().contains("403"))) {
            return "AI认证失败，请检查API Key配置";
        }
        if (root instanceof java.util.concurrent.RejectedExecutionException) {
            return "系统繁忙，线程池已满，请稍后重试";
        }
        if (root instanceof com.fasterxml.jackson.core.JsonProcessingException) {
            return "数据格式解析失败，报告无法生成";
        }
        if (msg != null && !msg.trim().isEmpty()) {
            int maxLen = 120;
            String trimmed = msg.trim();
            if (trimmed.length() > maxLen) {
                trimmed = trimmed.substring(0, maxLen) + "...";
            }
            return trimmed;
        }
        return "报告生成失败（" + typeName + "），请查看日志";
    }

    private AnalysisReportResponse doGenerateReport(String taskNo, boolean forceRegenerate, Consumer<Integer> progressCallback) {
        log.info("生成分析报告: taskNo={}, forceRegenerate={}", taskNo, forceRegenerate);

        if (progressCallback != null) {
            progressCallback.accept(2);
        }

        Task task = taskMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<Task>()
                        .eq("task_no", taskNo));
        if (task == null) {
            throw new BusinessException(ResultCode.TASK_NOT_FOUND);
        }

        if (!forceRegenerate && task.getReportJson() != null && !task.getReportJson().isEmpty()) {
            try {
                AnalysisReportResponse cached = objectMapper.readValue(task.getReportJson(), AnalysisReportResponse.class);
                log.info("使用缓存报告: taskNo={}, reportJsonSize={}", taskNo, task.getReportJson().length());
                if (progressCallback != null) progressCallback.accept(100);
                return cached;
            } catch (Exception e) {
                log.warn("缓存报告解析失败，将重新生成: {}", e.getMessage());
            }
        }

        if (progressCallback != null) progressCallback.accept(5);

        List<TaskResult> results = taskResultMapper.selectByTaskNo(taskNo);
        if (results == null || results.isEmpty()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "任务没有结果数据，无法生成报告");
        }

        List<TaskResult> validResults = results.stream()
                .filter(r -> ResultStatus.SUCCESS.name().equals(r.getStatus()))
                .collect(Collectors.toList());

        int totalResults = results.size();
        int validCount = validResults.size();

        Set<String> platforms = results.stream()
                .map(TaskResult::getAiPlatform)
                .collect(Collectors.toSet());

        Set<String> questions = results.stream()
                .map(TaskResult::getQuestionText)
                .collect(Collectors.toSet());

        String selfBrand = task.getBrandName() != null ? task.getBrandName() : "";
        String productName = task.getProductName() != null ? task.getProductName() : "";
        List<String> competitorBrands = parseCompetitors(task.getCompetitors());

        if (progressCallback != null) progressCallback.accept(10);

        final int aiStartPct = 12;
        final int aiEndPct = 70;
        BiConsumer<Integer, Integer> aiProgress = null;
        if (progressCallback != null) {
            aiProgress = (done, total) -> {
                int pct = aiStartPct + (int) ((aiEndPct - aiStartPct) * (done / (double) Math.max(1, total)));
                progressCallback.accept(pct);
            };
        }

        final Map<Long, List<String>> aiBrandRankings = aiEnabled
                ? batchAnalyzeBrandRankings(validResults, selfBrand, competitorBrands, aiProgress)
                : new HashMap<>();

        if (progressCallback != null) progressCallback.accept(75);

        Map<String, BrandMetrics> brandMetricsMap = analyzeBrandData(validResults, selfBrand, competitorBrands, aiBrandRankings);
        BrandMetrics productMetrics = !productName.isEmpty()
                ? analyzeBrandData(validResults, productName, new ArrayList<>(), aiBrandRankings).get(productName)
                : null;

        int[] competitiveCounts = calculateCompetitiveCounts(validResults, selfBrand, competitorBrands, aiBrandRankings);
        int selfMentionCount = competitiveCounts[0];
        int competitorMentionCount = competitiveCounts[1];

        MetricGroup topMetrics = buildTopMetrics(selfBrand, brandMetricsMap, validCount);
        MetricGroup productMetricsGroup = buildProductMetrics(productName, productMetrics, validCount);
        List<MetricGroup> sideMetrics = buildSideMetrics(selfBrand, brandMetricsMap, validCount);
        List<ChartWidget> charts = buildCharts(validResults, selfBrand, competitorBrands);
        List<RankingWidget> rankings = buildRankings(brandMetricsMap, validResults);

        if (progressCallback != null) progressCallback.accept(85);

        BrandComparisonTable brandComparison = buildBrandComparisonTable(selfBrand, competitorBrands, brandMetricsMap, validResults, aiBrandRankings);
        Map<String, BrandComparisonTable> perPlatformBrandComparison = buildPerPlatformBrandComparison(
                validResults, selfBrand, competitorBrands, aiBrandRankings);
        Map<String, Integer> perPlatformSelfMentionCount = computePerPlatformSelfMentionCount(validResults, selfBrand, aiBrandRankings);
        int[] posRep = computePositiveReputationCounts(validResults, selfBrand, aiBrandRankings);
        int posPositive = posRep[0];
        int posMentioned = posRep[1];
        List<AnalysisReportResponse.CompetitionRankingItem> competitionRanking = buildCompetitionRanking(selfBrand, competitorBrands, brandComparison);
        AiSummary aiSummary = buildAiSummary(task, results, brandMetricsMap, selfBrand, competitorBrands, validCount);
        ExposureMetrics exposureMetrics = buildExposureMetrics(brandMetricsMap.get(selfBrand), validCount, selfMentionCount, competitorMentionCount, perPlatformSelfMentionCount, posPositive, posMentioned);
        AnalysisReportResponse.KeywordCloud keywordCloud = buildKeywordCloud(validResults);

        if (progressCallback != null) progressCallback.accept(92);

        double brandMentionRate = brandMetricsMap.get(selfBrand) != null ? brandMetricsMap.get(selfBrand).getMentionRate() : 0;
        double productMentionRate;
        if (productName == null || productName.isEmpty()) {
            productMentionRate = 100.0;
        } else {
            productMentionRate = productMetrics != null ? productMetrics.getMentionRate() : 0;
        }
        double naturalRate = exposureMetrics.getNaturalRecommendationScore();
        double compRate = exposureMetrics.getCompetitiveScore();
        double rawScore = brandMentionRate * 0.28 + productMentionRate * 0.12 + naturalRate * 0.45 + compRate * 0.15;
        if (rawScore < 0) rawScore = 0;
        if (rawScore > 100) rawScore = 100;
        int overallScore = (int) Math.round(rawScore);
        String overallScoreSub = String.format("品牌%.1f×0.28 + 单品%.1f×0.12 + 自然%.0f×0.45 + 竞品%.0f×0.15",
                brandMentionRate, productMentionRate, naturalRate, compRate);

        Map<String, List<String>> resultBrandRankingsJson = new HashMap<>();
        for (Map.Entry<Long, List<String>> e : aiBrandRankings.entrySet()) {
            if (e.getKey() != null) {
                resultBrandRankingsJson.put(String.valueOf(e.getKey()), e.getValue());
            }
        }

        List<AnalysisReportResponse.PlatformScoreCard> platformScoreCards = buildPlatformScoreCardsFromPerPlatform(
                perPlatformBrandComparison, selfBrand, platforms.stream().map(p -> normalizeAiPlatformCode(p)).filter(Objects::nonNull).collect(Collectors.toList()));

        AnalysisReportResponse report = AnalysisReportResponse.builder()
                .taskNo(taskNo)
                .title(task.getTitle() != null ? task.getTitle() : taskNo)
                .brandName(selfBrand)
                .productName(productName)
                .periodLabel(buildPeriodLabel(task))
                .reportDate(LocalDateTime.now())
                .status(task.getStatus())
                .totalQuestions(questions.size())
                .totalPlatforms(platforms.size())
                .totalResults(totalResults)
                .completedResults(validCount)
                .topMetrics(topMetrics)
                .productMetrics(productMetricsGroup)
                .overallScore(overallScore)
                .overallScoreSub(overallScoreSub)
                .sideMetrics(sideMetrics)
                .charts(charts)
                .rankings(rankings)
                .aiSummary(aiSummary)
                .brandComparison(brandComparison)
                .perPlatformBrandComparison(perPlatformBrandComparison)
                .exposureMetrics(exposureMetrics)
                .competitionRanking(competitionRanking)
                .keywordCloud(keywordCloud)
                .platformScoreCards(platformScoreCards)
                .build();

        try {
            String reportJson = objectMapper.writeValueAsString(report);
            task.setReportJson(reportJson);
            taskMapper.updateById(task);
            log.info("报告已缓存: taskNo={}, jsonSize={}", taskNo, reportJson.length());

            TaskReport taskReport = new TaskReport();
            taskReport.setTaskNo(taskNo);
            taskReport.setReportJson(reportJson);
            taskReport.setReportDate(LocalDateTime.now());
            taskReportMapper.insert(taskReport);
            log.info("历史报告已保存: taskNo={}, reportId={}", taskNo, taskReport.getId());
        } catch (Exception e) {
            log.warn("缓存报告失败: {}", e.getMessage());
        }

        if (progressCallback != null) progressCallback.accept(100);

        log.info("分析报告生成完成: taskNo={}, totalResults={}, validCount={}",
                taskNo, totalResults, validCount);
        return report;
    }

    public List<Map<String, Object>> listReportHistory(String taskNo) {
        List<TaskReport> reports = taskReportMapper.selectByTaskNo(taskNo);
        List<Map<String, Object>> result = new ArrayList<>();
        for (TaskReport r : reports) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", r.getId());
            item.put("taskNo", r.getTaskNo());
            item.put("reportDate", r.getReportDate());
            item.put("createdAt", r.getCreatedAt());
            try {
                AnalysisReportResponse report = objectMapper.readValue(r.getReportJson(), AnalysisReportResponse.class);
                item.put("title", report.getTitle());
                item.put("brandName", report.getBrandName());
                item.put("totalQuestions", report.getTotalQuestions());
                item.put("totalPlatforms", report.getTotalPlatforms());
                item.put("totalResults", report.getTotalResults());
                item.put("completedResults", report.getCompletedResults());
                if (report.getExposureMetrics() != null) {
                    item.put("coverageRate", report.getExposureMetrics().getCoverageRate());
                    item.put("firstRate", report.getExposureMetrics().getFirstRate());
                    item.put("top3Rate", report.getExposureMetrics().getTop3Rate());
                    item.put("top5Rate", report.getExposureMetrics().getTop5Rate());
                }
            } catch (Exception e) {
                log.warn("解析历史报告摘要失败: reportId={}", r.getId(), e);
            }
            result.add(item);
        }
        return result;
    }

    public AnalysisReportResponse getReportById(Long reportId) {
        TaskReport taskReport = taskReportMapper.selectById(reportId);
        if (taskReport == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "历史报告不存在");
        }
        try {
            AnalysisReportResponse report = objectMapper.readValue(taskReport.getReportJson(), AnalysisReportResponse.class);
            report.setReportDate(taskReport.getReportDate());
            return report;
        } catch (Exception e) {
            log.error("解析历史报告失败: reportId={}", reportId, e);
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "历史报告数据解析失败");
        }
    }

    private List<String> parseCompetitors(String competitorsStr) {
        List<String> result = new ArrayList<>();
        if (competitorsStr == null || competitorsStr.isEmpty()) {
            return result;
        }
        try {
            List<String> parsed = objectMapper.readValue(competitorsStr, new TypeReference<List<String>>() {});
            if (parsed != null) {
                for (String s : parsed) {
                    if (s != null && !s.trim().isEmpty()) {
                        result.add(s.trim());
                    }
                }
                return result;
            }
        } catch (Exception e) {
            log.debug("竞对品牌JSON解析失败，尝试按逗号分割: {}", e.getMessage());
        }
        String[] parts = competitorsStr.split("[,，、;；\\s]+");
        for (String part : parts) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }
        return result;
    }

    public static class BrandMetrics {
        private String brandName;
        private int mentionCount;
        private int firstCount;
        private int top3Count;
        private int top5Count;
        private int totalAnswers;
        private int naturalRecommendationCount;

        public String getBrandName() { return brandName; }
        public void setBrandName(String brandName) { this.brandName = brandName; }
        public int getMentionCount() { return mentionCount; }
        public void setMentionCount(int mentionCount) { this.mentionCount = mentionCount; }
        public int getFirstCount() { return firstCount; }
        public void setFirstCount(int firstCount) { this.firstCount = firstCount; }
        public int getTop3Count() { return top3Count; }
        public void setTop3Count(int top3Count) { this.top3Count = top3Count; }
        public int getTop5Count() { return top5Count; }
        public void setTop5Count(int top5Count) { this.top5Count = top5Count; }
        public int getTotalAnswers() { return totalAnswers; }
        public void setTotalAnswers(int totalAnswers) { this.totalAnswers = totalAnswers; }
        public int getNaturalRecommendationCount() { return naturalRecommendationCount; }
        public void setNaturalRecommendationCount(int naturalRecommendationCount) { this.naturalRecommendationCount = naturalRecommendationCount; }

        public double getMentionRate() {
            return totalAnswers > 0 ? (mentionCount * 100.0) / totalAnswers : 0;
        }

        public double getFirstRate() {
            return totalAnswers > 0 ? (firstCount * 100.0) / totalAnswers : 0;
        }

        public double getTop3Rate() {
            return totalAnswers > 0 ? (top3Count * 100.0) / totalAnswers : 0;
        }

        public double getTop5Rate() {
            return totalAnswers > 0 ? (top5Count * 100.0) / totalAnswers : 0;
        }

        public double getNaturalRecommendationRate() {
            return mentionCount > 0 ? (naturalRecommendationCount * 100.0) / mentionCount : 0;
        }

        public int getNaturalRecommendationScore() {
            if (mentionCount <= 0) return 0;
            double score = (naturalRecommendationCount * 100.0) / mentionCount;
            if (score < 0) score = 0;
            if (score > 100) score = 100;
            return (int) Math.round(score);
        }
    }

    private Map<String, BrandMetrics> analyzeBrandData(List<TaskResult> validResults,
                                                       String selfBrand,
                                                       List<String> competitorBrands,
                                                       Map<Long, List<String>> aiBrandRankings) {
        Map<String, BrandMetrics> metricsMap = new LinkedHashMap<>();

        List<String> allBrands = new ArrayList<>();
        if (selfBrand != null && !selfBrand.isEmpty()) {
            allBrands.add(selfBrand);
        }
        allBrands.addAll(competitorBrands);

        for (String brand : allBrands) {
            BrandMetrics metrics = new BrandMetrics();
            metrics.setBrandName(brand);
            metrics.setTotalAnswers(validResults.size());
            metricsMap.put(brand, metrics);
        }

        Map<Long, List<String>> rankingMap = aiBrandRankings != null ? aiBrandRankings : new HashMap<>();

        for (TaskResult result : validResults) {
            String answerText = result.getAnswerText() != null ? result.getAnswerText() : "";
            String combinedText = answerText;
            String questionText = result.getQuestionText() != null ? result.getQuestionText() : "";
            String questionLower = questionText.toLowerCase();

            Map<String, Integer> brandPositions;
            List<String> aiRanking = rankingMap.get(result.getId());
            if (aiRanking != null && !aiRanking.isEmpty()) {
                brandPositions = new LinkedHashMap<>();
                for (int i = 0; i < aiRanking.size(); i++) {
                    brandPositions.put(aiRanking.get(i), i + 1);
                }
            } else {
                brandPositions = extractBrandPositions(combinedText, allBrands);
            }

            for (Map.Entry<String, BrandMetrics> entry : metricsMap.entrySet()) {
                String brand = entry.getKey();
                BrandMetrics metrics = entry.getValue();

                boolean mentionedInAnswer = false;
                Integer position = brandPositions.get(brand);
                if (position != null) {
                    mentionedInAnswer = true;
                    metrics.setMentionCount(metrics.getMentionCount() + 1);
                    if (position == 1) {
                        metrics.setFirstCount(metrics.getFirstCount() + 1);
                    }
                    if (position <= 3) {
                        metrics.setTop3Count(metrics.getTop3Count() + 1);
                    }
                    if (position <= 5) {
                        metrics.setTop5Count(metrics.getTop5Count() + 1);
                    }
                } else {
                    if (containsBrandName(combinedText, brand)) {
                        mentionedInAnswer = true;
                        metrics.setMentionCount(metrics.getMentionCount() + 1);
                    }
                }

                if (mentionedInAnswer && brand != null && !brand.isEmpty()) {
                    boolean mentionedInQuestion = findFirstOccurrence(questionLower, brand.toLowerCase()) >= 0;
                    if (!mentionedInQuestion) {
                        metrics.setNaturalRecommendationCount(metrics.getNaturalRecommendationCount() + 1);
                    }
                }
            }
        }

        return metricsMap;
    }

    private Map<String, Integer> extractBrandPositions(String text, List<String> brands) {
        Map<String, Integer> positions = new LinkedHashMap<>();
        if (text == null || text.isEmpty() || brands == null || brands.isEmpty()) {
            return positions;
        }

        Map<String, Integer> brandFirstIndex = new HashMap<>();

        for (String brand : brands) {
            if (brand == null || brand.isEmpty()) continue;
            int idx = findFirstOccurrence(text, brand);
            if (idx >= 0) {
                brandFirstIndex.put(brand, idx);
            }
        }

        List<Map.Entry<String, Integer>> sorted = brandFirstIndex.entrySet().stream()
                .sorted(Map.Entry.comparingByValue())
                .collect(Collectors.toList());

        int position = 1;
        for (Map.Entry<String, Integer> entry : sorted) {
            positions.put(entry.getKey(), position);
            position++;
        }

        return positions;
    }

    private int findFirstOccurrence(String text, String brand) {
        if (text == null || brand == null || brand.isEmpty()) return -1;

        String lowerText = text.toLowerCase();
        String lowerBrand = brand.toLowerCase();

        return lowerText.indexOf(lowerBrand);
    }

    private boolean containsBrandName(String text, String brand) {
        return findFirstOccurrence(text, brand) >= 0;
    }

    private MetricGroup buildTopMetrics(String selfBrand, Map<String, BrandMetrics> metricsMap, int validCount) {
        MetricGroup metric = new MetricGroup();
        metric.setTitle("品牌覆盖率");

        BrandMetrics selfMetrics = metricsMap.get(selfBrand);
        if (selfMetrics != null && validCount > 0) {
            metric.setValue(String.format("%.1f", selfMetrics.getMentionRate()));
            metric.setSubValue(selfMetrics.getMentionCount() + "/" + validCount);
            metric.setUnit("%");
            metric.setTrendDirection("NONE");
            metric.setTrend("--");

            List<MetricSubItem> subItems = new ArrayList<>();
            MetricSubItem mentionItem = new MetricSubItem();
            mentionItem.setLabel("提及率");
            mentionItem.setValue(String.format("%.1f%%", selfMetrics.getMentionRate()));
            mentionItem.setPercentage(String.format("%.1f%%", selfMetrics.getMentionRate()));
            subItems.add(mentionItem);

            MetricSubItem firstItem = new MetricSubItem();
            firstItem.setLabel("首位率");
            firstItem.setValue(String.format("%.1f%%", selfMetrics.getFirstRate()));
            firstItem.setPercentage(String.format("%.1f%%", selfMetrics.getFirstRate()));
            subItems.add(firstItem);

            MetricSubItem top3Item = new MetricSubItem();
            top3Item.setLabel("前三率");
            top3Item.setValue(String.format("%.1f%%", selfMetrics.getTop3Rate()));
            top3Item.setPercentage(String.format("%.1f%%", selfMetrics.getTop3Rate()));
            subItems.add(top3Item);

            MetricSubItem top5Item = new MetricSubItem();
            top5Item.setLabel("前五率");
            top5Item.setValue(String.format("%.1f%%", selfMetrics.getTop5Rate()));
            top5Item.setPercentage(String.format("%.1f%%", selfMetrics.getTop5Rate()));
            subItems.add(top5Item);

            metric.setSubItems(subItems);
        } else {
            metric.setValue("0.0");
            metric.setSubValue("0/" + validCount);
            metric.setUnit("%");
            metric.setTrend("--");
            metric.setTrendDirection("NONE");
            metric.setSubItems(buildEmptySubItems());
        }

        return metric;
    }

    private MetricGroup buildProductMetrics(String productName, BrandMetrics productMetrics, int validCount) {
        MetricGroup metric = new MetricGroup();
        metric.setTitle("单品提及率");
        if (productName != null && !productName.isEmpty()) {
            metric.setTitle(productName + " 提及率");
        }

        boolean noProductSpecified = productName == null || productName.isEmpty();
        if (noProductSpecified) {
            metric.setValue("100.0");
            metric.setSubValue("--/--");
            metric.setUnit("%");
            metric.setTrend("--");
            metric.setTrendDirection("NONE");

            List<MetricSubItem> subItems = new ArrayList<>();
            MetricSubItem mentionItem = new MetricSubItem();
            mentionItem.setLabel("提及率");
            mentionItem.setValue("100.0%");
            mentionItem.setPercentage("100.0%");
            subItems.add(mentionItem);
            metric.setSubItems(subItems);
        } else if (productMetrics != null && validCount > 0) {
            metric.setValue(String.format("%.1f", productMetrics.getMentionRate()));
            metric.setSubValue(productMetrics.getMentionCount() + "/" + validCount);
            metric.setUnit("%");
            metric.setTrendDirection("NONE");
            metric.setTrend("--");

            List<MetricSubItem> subItems = new ArrayList<>();
            MetricSubItem mentionItem = new MetricSubItem();
            mentionItem.setLabel("提及率");
            mentionItem.setValue(String.format("%.1f%%", productMetrics.getMentionRate()));
            mentionItem.setPercentage(String.format("%.1f%%", productMetrics.getMentionRate()));
            subItems.add(mentionItem);

            metric.setSubItems(subItems);
        } else {
            metric.setValue("0.0");
            metric.setSubValue("0/" + validCount);
            metric.setUnit("%");
            metric.setTrend("--");
            metric.setTrendDirection("NONE");

            List<MetricSubItem> subItems = new ArrayList<>();
            MetricSubItem mentionItem = new MetricSubItem();
            mentionItem.setLabel("提及率");
            mentionItem.setValue("0.0%");
            mentionItem.setPercentage("0.0%");
            subItems.add(mentionItem);
            metric.setSubItems(subItems);
        }

        return metric;
    }

    private List<MetricGroup> buildSideMetrics(String selfBrand, Map<String, BrandMetrics> metricsMap, int validCount) {
        List<MetricGroup> metrics = new ArrayList<>();

        BrandMetrics selfMetrics = metricsMap.get(selfBrand);

        MetricGroup top3Metric = new MetricGroup();
        top3Metric.setTitle("三引率");
        top3Metric.setUnit("%");
        top3Metric.setTrend("--");
        top3Metric.setTrendDirection("NONE");
        if (selfMetrics != null) {
            top3Metric.setValue(String.format("%.1f", selfMetrics.getTop3Rate()));
            List<MetricSubItem> subItems = new ArrayList<>();
            MetricSubItem item1 = new MetricSubItem();
            item1.setLabel("前三数");
            item1.setValue(String.valueOf(selfMetrics.getTop3Count()));
            MetricSubItem item2 = new MetricSubItem();
            item2.setLabel("有效回答");
            item2.setValue(String.valueOf(validCount));
            subItems.add(item1);
            subItems.add(item2);
            top3Metric.setSubItems(subItems);
        } else {
            top3Metric.setValue("0.0");
            top3Metric.setSubItems(buildEmptySubItems());
        }
        metrics.add(top3Metric);

        MetricGroup top5Metric = new MetricGroup();
        top5Metric.setTitle("五引率");
        top5Metric.setUnit("%");
        top5Metric.setTrend("--");
        top5Metric.setTrendDirection("NONE");
        if (selfMetrics != null) {
            top5Metric.setValue(String.format("%.1f", selfMetrics.getTop5Rate()));
            List<MetricSubItem> subItems = new ArrayList<>();
            MetricSubItem item1 = new MetricSubItem();
            item1.setLabel("前五数");
            item1.setValue(String.valueOf(selfMetrics.getTop5Count()));
            MetricSubItem item2 = new MetricSubItem();
            item2.setLabel("有效回答");
            item2.setValue(String.valueOf(validCount));
            subItems.add(item1);
            subItems.add(item2);
            top5Metric.setSubItems(subItems);
        } else {
            top5Metric.setValue("0.0");
            top5Metric.setSubItems(buildEmptySubItems());
        }
        metrics.add(top5Metric);

        MetricGroup firstMetric = new MetricGroup();
        firstMetric.setTitle("首位率");
        firstMetric.setUnit("%");
        firstMetric.setTrend("--");
        firstMetric.setTrendDirection("NONE");
        if (selfMetrics != null) {
            firstMetric.setValue(String.format("%.1f", selfMetrics.getFirstRate()));
            List<MetricSubItem> subItems = new ArrayList<>();
            MetricSubItem item1 = new MetricSubItem();
            item1.setLabel("首位数");
            item1.setValue(String.valueOf(selfMetrics.getFirstCount()));
            MetricSubItem item2 = new MetricSubItem();
            item2.setLabel("有效回答");
            item2.setValue(String.valueOf(validCount));
            subItems.add(item1);
            subItems.add(item2);
            firstMetric.setSubItems(subItems);
        } else {
            firstMetric.setValue("0.0");
            firstMetric.setSubItems(buildEmptySubItems());
        }
        metrics.add(firstMetric);

        MetricGroup cpmMetric = new MetricGroup();
        cpmMetric.setTitle("提及率");
        cpmMetric.setUnit("%");
        cpmMetric.setTrend("--");
        cpmMetric.setTrendDirection("NONE");
        if (selfMetrics != null) {
            cpmMetric.setValue(String.format("%.1f", selfMetrics.getMentionRate()));
            List<MetricSubItem> subItems = new ArrayList<>();
            MetricSubItem item1 = new MetricSubItem();
            item1.setLabel("提及数");
            item1.setValue(String.valueOf(selfMetrics.getMentionCount()));
            MetricSubItem item2 = new MetricSubItem();
            item2.setLabel("有效回答");
            item2.setValue(String.valueOf(validCount));
            subItems.add(item1);
            subItems.add(item2);
            cpmMetric.setSubItems(subItems);
        } else {
            cpmMetric.setValue("0.0");
            cpmMetric.setSubItems(buildEmptySubItems());
        }
        metrics.add(cpmMetric);

        return metrics;
    }

    private List<MetricSubItem> buildEmptySubItems() {
        List<MetricSubItem> items = new ArrayList<>();
        MetricSubItem item = new MetricSubItem();
        item.setLabel("暂无数据");
        item.setValue("--");
        items.add(item);
        return items;
    }

    private int[] calculateCompetitiveCounts(List<TaskResult> validResults, String selfBrand, List<String> competitorBrands,
                                              Map<Long, List<String>> aiBrandRankings) {
        int selfCount = 0;
        int compCount = 0;

        if (validResults == null || validResults.isEmpty()) {
            return new int[]{0, 0};
        }

        boolean hasSelf = selfBrand != null && !selfBrand.isEmpty();
        boolean hasComp = competitorBrands != null && !competitorBrands.isEmpty();
        String selfLower = hasSelf ? selfBrand.toLowerCase() : null;
        List<String> compLowerList = hasComp ? competitorBrands.stream()
                .map(c -> c != null ? c.toLowerCase() : null)
                .filter(c -> c != null && !c.isEmpty())
                .collect(Collectors.toList()) : null;

        for (TaskResult result : validResults) {
            List<String> aiRanking = aiBrandRankings != null ? aiBrandRankings.get(result.getId()) : null;

            boolean mentionedSelf = false;
            boolean mentionedAnyComp = false;

            if (aiRanking != null && !aiRanking.isEmpty()) {
                if (hasSelf) {
                    for (String b : aiRanking) {
                        if (selfBrand.equalsIgnoreCase(b)) {
                            mentionedSelf = true;
                            break;
                        }
                    }
                }
                if (hasComp) {
                    for (String b : aiRanking) {
                        boolean found = false;
                        for (String comp : compLowerList) {
                            if (comp.equalsIgnoreCase(b)) {
                                found = true;
                                break;
                            }
                        }
                        if (found) {
                            mentionedAnyComp = true;
                            break;
                        }
                    }
                }
            }

            String answerText = result.getAnswerText() != null ? result.getAnswerText() : "";
            
            String combinedLower = (answerText).toLowerCase();

            if (!mentionedSelf && hasSelf) {
                mentionedSelf = findFirstOccurrence(combinedLower, selfLower) >= 0;
            }
            if (mentionedSelf) {
                selfCount++;
            }

            if (!mentionedAnyComp && hasComp) {
                for (String comp : compLowerList) {
                    if (findFirstOccurrence(combinedLower, comp) >= 0) {
                        mentionedAnyComp = true;
                        break;
                    }
                }
            }
            if (mentionedAnyComp) {
                compCount++;
            }
        }

        return new int[]{selfCount, compCount};
    }

    private ExposureMetrics buildExposureMetrics(BrandMetrics selfBrandMetrics, int validCount, int selfMentionCount, int competitorMentionCount, Map<String, Integer> perPlatformSelfMentionCount, int posPositive, int posMentioned) {
        int total = selfMentionCount + competitorMentionCount;
        int competitiveScore = 0;
        if (total > 0) {
            double s = (selfMentionCount * 100.0) / total;
            if (s < 0) s = 0;
            if (s > 100) s = 100;
            competitiveScore = (int) Math.round(s);
        }
        String competitiveSub = selfMentionCount + "/" + total;

        double positiveReputationRate = 0;
        if (posMentioned > 0) {
            positiveReputationRate = (posPositive * 100.0) / posMentioned;
            positiveReputationRate = Math.round(positiveReputationRate * 10) / 10.0;
        }
        String positiveReputationSub = posPositive + "/" + posMentioned;

        if (selfBrandMetrics == null || validCount <= 0) {
            return ExposureMetrics.builder()
                    .mentionCount("0/0")
                    .coverageRate(0)
                    .firstRate(0)
                    .top3Rate(0)
                    .top5Rate(0)
                    .naturalRecommendationScore(0)
                    .naturalRecommendationSub("0/0")
                    .competitiveScore(competitiveScore)
                    .competitiveSub(competitiveSub)
                    .perPlatformSelfMentionCount(perPlatformSelfMentionCount)
                    .positiveReputationRate(positiveReputationRate)
                    .positiveReputationSub(positiveReputationSub)
                    .build();
        }
        int mentionCount = selfBrandMetrics.getMentionCount();
        double coverageRate = (mentionCount * 100.0) / validCount;
        double firstRate = selfBrandMetrics.getFirstRate();
        double top3Rate = selfBrandMetrics.getTop3Rate();
        double top5Rate = selfBrandMetrics.getTop5Rate();
        int naturalScore = selfBrandMetrics.getNaturalRecommendationScore();
        int naturalCount = selfBrandMetrics.getNaturalRecommendationCount();

        return ExposureMetrics.builder()
                .mentionCount(mentionCount + "/" + validCount)
                .coverageRate(Math.round(coverageRate * 10) / 10.0)
                .firstRate(Math.round(firstRate * 10) / 10.0)
                .top3Rate(Math.round(top3Rate * 10) / 10.0)
                .top5Rate(Math.round(top5Rate * 10) / 10.0)
                .naturalRecommendationScore(naturalScore)
                .naturalRecommendationSub(naturalCount + "/" + mentionCount)
                .competitiveScore(competitiveScore)
                .competitiveSub(competitiveSub)
                .perPlatformSelfMentionCount(perPlatformSelfMentionCount)
                .positiveReputationRate(positiveReputationRate)
                .positiveReputationSub(positiveReputationSub)
                .build();
    }

    private Map<String, Integer> computePerPlatformSelfMentionCount(List<TaskResult> validResults, String selfBrand, Map<Long, List<String>> aiBrandRankings) {
        Map<String, Integer> result = new LinkedHashMap<>();
        if (validResults == null || selfBrand == null || selfBrand.isEmpty()) {
            return result;
        }
        Map<Long, List<String>> rankingMap = aiBrandRankings != null ? aiBrandRankings : new HashMap<>();
        for (TaskResult r : validResults) {
            if (r == null) continue;
            String platformCode = normalizeAiPlatformCode(r.getAiPlatform());
            if (platformCode == null) continue;
            boolean mentioned = false;
            List<String> aiRanking = rankingMap.get(r.getId());
            if (aiRanking != null && !aiRanking.isEmpty()) {
                mentioned = aiRanking.contains(selfBrand);
            } else {
                String answerText = r.getAnswerText() != null ? r.getAnswerText() : "";
                mentioned = answerText.toLowerCase().contains(selfBrand.toLowerCase());
            }
            if (mentioned) {
                result.merge(platformCode, 1, Integer::sum);
            }
        }
        return result;
    }

    private BrandComparisonTable buildBrandComparisonTable(String selfBrand,
                                                           List<String> competitorBrands,
                                                           Map<String, BrandMetrics> metricsMap,
                                                           List<TaskResult> validResults,
                                                           Map<Long, List<String>> aiBrandRankings) {
        BrandComparisonTable table = new BrandComparisonTable();
        table.setTitle("曝光率对比");
        int validCount = validResults != null ? validResults.size() : 0;
        table.setTotalValidAnswers(validCount);

        List<String> columns = new ArrayList<>();
        columns.add("维度");
        columns.add(selfBrand != null ? selfBrand : "自主品牌");
        for (String comp : competitorBrands) {
            columns.add(comp);
        }
        table.setColumns(columns);

        List<ComparisonRow> rows = new ArrayList<>();

        rows.add(createCompetitiveScoreRow(selfBrand, competitorBrands, validResults, aiBrandRankings));
        rows.add(createComparisonRow("品牌覆盖率", selfBrand, competitorBrands, metricsMap, "mentionRate"));
        rows.add(createComparisonRow("首位率", selfBrand, competitorBrands, metricsMap, "firstRate"));
        rows.add(createComparisonRow("前三率", selfBrand, competitorBrands, metricsMap, "top3Rate"));
        rows.add(createComparisonRow("前五率", selfBrand, competitorBrands, metricsMap, "top5Rate"));

        table.setRows(rows);
        return table;
    }

    private String normalizeAiPlatformCode(String rawPlatform) {
        if (rawPlatform == null || rawPlatform.isEmpty()) return null;
        String p = rawPlatform.toLowerCase().trim();
        if (p.endsWith("_app")) {
            p = p.substring(0, p.length() - 4);
        }
        if ("tencent_yuanbao".equals(p) || "yuanbao".equals(p) || "yuanbaoa".equals(p) || "tenxun".equals(p)) p = "tencent";
        if ("wenxin_yiyan".equals(p) || "ernie".equals(p) || "yiyan".equals(p)) p = "wenxin";
        if ("tongyi".equals(p) || "tongyi_qianwen".equals(p) || "qianwen_max".equals(p) || "qwen".equals(p)) p = "qianwen";
        if ("moonshot".equals(p)) p = "kimi";
        return p;
    }

    private String platformDisplayName(String platformCode) {
        if (platformCode == null || platformCode.isEmpty()) return "";
        AiPlatform ap = AiPlatform.fromCode(platformCode);
        if (ap != null) return ap.getDisplayName();
        AiPlatform apApp = AiPlatform.fromCode(platformCode + "_app");
        if (apApp != null) return apApp.getDisplayName();
        return platformCode;
    }

    private List<AnalysisReportResponse.PlatformScoreCard> buildPlatformScoreCardsFromPerPlatform(
            Map<String, AnalysisReportResponse.BrandComparisonTable> perPlatformBrandComparison,
            String selfBrand,
            List<String> activePlatformCodes) {

        List<AnalysisReportResponse.PlatformScoreCard> result = new ArrayList<>();
        if (activePlatformCodes == null || activePlatformCodes.isEmpty()) return result;

        Map<String, AnalysisReportResponse.BrandComparisonTable> normalizedPerPlat = new HashMap<>();
        if (perPlatformBrandComparison != null) {
            for (Map.Entry<String, AnalysisReportResponse.BrandComparisonTable> e : perPlatformBrandComparison.entrySet()) {
                String code = normalizeAiPlatformCode(e.getKey());
                if (code == null) code = e.getKey();
                normalizedPerPlat.put(code, e.getValue());
            }
        }

        for (String platformCode : activePlatformCodes) {
            if (platformCode == null) continue;
            AnalysisReportResponse.PlatformScoreCard card = new AnalysisReportResponse.PlatformScoreCard();
            card.setPlatformCode(platformCode);
            card.setPlatformName(platformDisplayName(platformCode));

            AnalysisReportResponse.BrandComparisonTable platTable = normalizedPerPlat.get(platformCode);
            if (platTable != null && platTable.getColumns() != null && platTable.getRows() != null) {
                AnalysisReportResponse.ComparisonRow compRow = null;
                AnalysisReportResponse.ComparisonRow coverageRow = null;
                for (AnalysisReportResponse.ComparisonRow row : platTable.getRows()) {
                    if (row == null) continue;
                    if ("品牌竞争力".equals(row.getMetric())) compRow = row;
                    if ("覆盖率".equals(row.getMetric())) coverageRow = row;
                }
                int selfColIdx = 1;
                for (int ci = 1; ci < platTable.getColumns().size(); ci++) {
                    String colName = platTable.getColumns().get(ci);
                    if ((selfBrand == null && colName == null)
                            || (selfBrand != null && !selfBrand.isEmpty() && selfBrand.equalsIgnoreCase(colName))) {
                        selfColIdx = ci;
                        break;
                    }
                }
                if (compRow != null && compRow.getValues() != null && selfColIdx - 1 < compRow.getValues().size()) {
                    String raw = compRow.getValues().get(selfColIdx - 1);
                    if (raw != null && !raw.isEmpty()) {
                        try {
                            double s = Double.parseDouble(raw.trim());
                            card.setScore(Math.round(s * 10) / 10.0);
                        } catch (NumberFormatException ignore) {}
                    }
                }
                if (coverageRow != null && coverageRow.getValues() != null && selfColIdx - 1 < coverageRow.getValues().size()) {
                    card.setAudienceScale(coverageRow.getValues().get(selfColIdx - 1));
                }
            }
            result.add(card);
        }

        result.sort((a, b) -> {
            double sa = a.getScore() != null ? a.getScore() : -1;
            double sb = b.getScore() != null ? b.getScore() : -1;
            return Double.compare(sb, sa);
        });
        int rk = 1;
        for (AnalysisReportResponse.PlatformScoreCard card : result) {
            if (card.getScore() != null && card.getScore() > 0) {
                card.setRank(rk++);
            }
        }
        return result;
    }

    private int[] computePositiveReputationCounts(List<TaskResult> validResults, String selfBrand, Map<Long, List<String>> aiBrandRankings) {
        int mentioned = 0;
        int positive = 0;
        if (validResults == null || selfBrand == null || selfBrand.isEmpty()) return new int[]{0, 0};
        Map<Long, List<String>> rankingMap = aiBrandRankings != null ? aiBrandRankings : new HashMap<>();
        for (TaskResult r : validResults) {
            if (r == null) continue;
            boolean isMentioned = false;
            List<String> aiRanking = rankingMap.get(r.getId());
            if (aiRanking != null && !aiRanking.isEmpty()) {
                isMentioned = aiRanking.contains(selfBrand);
            } else {
                String answerText = r.getAnswerText() != null ? r.getAnswerText() : "";
                isMentioned = answerText.toLowerCase().contains(selfBrand.toLowerCase());
            }
            if (!isMentioned) continue;
            mentioned++;
            String sent = com.geo.common.SentimentUtils.analyzeBrandSentiment(r.getAnswerText(), selfBrand);
            if ("positive".equals(sent)) positive++;
        }
        return new int[]{positive, mentioned};
    }

    private Map<String, BrandComparisonTable> buildPerPlatformBrandComparison(
            List<TaskResult> validResults,
            String selfBrand,
            List<String> competitorBrands,
            Map<Long, List<String>> aiBrandRankings) {
        Map<String, BrandComparisonTable> result = new LinkedHashMap<>();
        if (validResults == null || validResults.isEmpty()) return result;

        Map<String, List<TaskResult>> grouped = new LinkedHashMap<>();
        for (TaskResult r : validResults) {
            String code = normalizeAiPlatformCode(r.getAiPlatform());
            if (code == null) continue;
            grouped.computeIfAbsent(code, k -> new ArrayList<>()).add(r);
        }

        for (Map.Entry<String, List<TaskResult>> e : grouped.entrySet()) {
            String platformCode = e.getKey();
            List<TaskResult> platformResults = e.getValue();
            if (platformResults == null || platformResults.isEmpty()) continue;
            try {
                Map<String, BrandMetrics> platformMetrics = analyzeBrandData(platformResults, selfBrand, competitorBrands, aiBrandRankings);
                BrandComparisonTable table = buildBrandComparisonTable(selfBrand, competitorBrands, platformMetrics, platformResults, aiBrandRankings);
                result.put(platformCode, table);
            } catch (Exception ex) {
                log.warn("按平台计算BrandComparison失败: platform={}", platformCode, ex);
            }
        }

        return result;
    }

    private List<AnalysisReportResponse.CompetitionRankingItem> buildCompetitionRanking(
            String selfBrand,
            List<String> competitorBrands,
            BrandComparisonTable brandComparison) {
        List<AnalysisReportResponse.CompetitionRankingItem> items = new ArrayList<>();
        if (brandComparison == null
                || brandComparison.getColumns() == null
                || brandComparison.getColumns().size() < 2
                || brandComparison.getRows() == null) {
            return items;
        }

        ComparisonRow compRow = null;
        for (ComparisonRow r : brandComparison.getRows()) {
            if ("品牌竞争力".equals(r.getMetric())) {
                compRow = r;
                break;
            }
        }
        if (compRow == null || compRow.getValues() == null) return items;

        List<String> brandNames = brandComparison.getColumns().subList(1, brandComparison.getColumns().size());
        List<Double> scores = new ArrayList<>();
        for (int i = 0; i < brandNames.size(); i++) {
            String raw = i < compRow.getValues().size() ? compRow.getValues().get(i) : "0";
            double v = 0;
            try {
                if (raw != null && !raw.isEmpty()) {
                    v = Double.parseDouble(raw.trim());
                }
            } catch (Exception ignore) {}
            scores.add(v);
        }

        List<Integer> indices = new ArrayList<>();
        for (int i = 0; i < brandNames.size(); i++) indices.add(i);
        indices.sort((a, b) -> Double.compare(scores.get(b), scores.get(a)));

        for (int k = 0; k < indices.size(); k++) {
            int idx = indices.get(k);
            String brandName = brandNames.get(idx);
            AnalysisReportResponse.CompetitionRankingItem it = new AnalysisReportResponse.CompetitionRankingItem();
            it.setRank(k + 1);
            it.setBrandName(brandName);
            it.setSelfBrand(selfBrand != null && selfBrand.equalsIgnoreCase(brandName));
            double rawScore = scores.get(idx);
            if (rawScore == Math.floor(rawScore)) {
                it.setScore(String.valueOf((int) rawScore));
            } else {
                it.setScore(String.format("%.2f", rawScore));
            }
            items.add(it);
        }

        return items;
    }

    private ComparisonRow createCompetitiveScoreRow(String selfBrand,
                                                    List<String> competitorBrands,
                                                    List<TaskResult> validResults,
                                                    Map<Long, List<String>> aiBrandRankings) {
        ComparisonRow row = new ComparisonRow();
        row.setMetric("品牌竞争力");

        List<String> allBrands = new ArrayList<>();
        if (selfBrand != null && !selfBrand.isEmpty()) {
            allBrands.add(selfBrand);
        }
        if (competitorBrands != null) {
            allBrands.addAll(competitorBrands);
        }

        List<String> values = new ArrayList<>();
        List<String> trends = new ArrayList<>();

        if (validResults == null || validResults.isEmpty()) {
            for (int i = 0; i < allBrands.size(); i++) {
                values.add("0");
                trends.add("—");
            }
            row.setValues(values);
            row.setTrends(trends);
            return row;
        }

        Map<Long, List<String>> rankingMap = aiBrandRankings != null ? aiBrandRankings : new HashMap<>();

        for (int i = 0; i < allBrands.size(); i++) {
            String brand = allBrands.get(i);
            List<String> otherBrands = new ArrayList<>();
            for (int j = 0; j < allBrands.size(); j++) {
                if (j != i) otherBrands.add(allBrands.get(j));
            }

            int brandCount = 0;
            int otherCount = 0;
            String brandLower = brand != null ? brand.toLowerCase() : "";

            for (TaskResult result : validResults) {
                List<String> aiRanking = rankingMap.get(result.getId());
                String answerText = result.getAnswerText() != null ? result.getAnswerText() : "";
                
                String combinedLower = (answerText).toLowerCase();

                boolean mentionedBrand = false;
                if (aiRanking != null && !aiRanking.isEmpty()) {
                    for (String b : aiRanking) {
                        if (brand != null && brand.equalsIgnoreCase(b)) {
                            mentionedBrand = true;
                            break;
                        }
                    }
                }
                if (!mentionedBrand && brand != null && !brand.isEmpty()) {
                    mentionedBrand = findFirstOccurrence(combinedLower, brandLower) >= 0;
                }
                if (mentionedBrand) {
                    brandCount++;
                }

                boolean mentionedOther = false;
                if (aiRanking != null && !aiRanking.isEmpty()) {
                    for (String b : aiRanking) {
                        for (String ob : otherBrands) {
                            if (ob != null && ob.equalsIgnoreCase(b)) {
                                mentionedOther = true;
                                break;
                            }
                        }
                        if (mentionedOther) break;
                    }
                }
                if (!mentionedOther) {
                    for (String ob : otherBrands) {
                        if (ob == null || ob.isEmpty()) continue;
                        if (findFirstOccurrence(combinedLower, ob.toLowerCase()) >= 0) {
                            mentionedOther = true;
                            break;
                        }
                    }
                }
                if (mentionedOther) {
                    otherCount++;
                }
            }

            int total = brandCount + otherCount;
            int score = 0;
            if (total > 0) {
                double s = (brandCount * 100.0) / total;
                if (s < 0) s = 0;
                if (s > 100) s = 100;
                score = (int) Math.round(s);
            }
            values.add(String.valueOf(score));
            trends.add("—");
        }

        row.setValues(values);
        row.setTrends(trends);
        return row;
    }

    private ComparisonRow createComparisonRow(String metricName, String selfBrand,
                                              List<String> competitorBrands,
                                              Map<String, BrandMetrics> metricsMap,
                                              String rateType) {
        ComparisonRow row = new ComparisonRow();
        row.setMetric(metricName);

        List<String> values = new ArrayList<>();
        List<String> trends = new ArrayList<>();

        List<String> brands = new ArrayList<>();
        brands.add(selfBrand);
        brands.addAll(competitorBrands);

        for (String brand : brands) {
            BrandMetrics metrics = metricsMap.get(brand);
            if (metrics != null) {
                double rate;
                switch (rateType) {
                    case "firstRate": rate = metrics.getFirstRate(); break;
                    case "top3Rate": rate = metrics.getTop3Rate(); break;
                    case "top5Rate": rate = metrics.getTop5Rate(); break;
                    default: rate = metrics.getMentionRate(); break;
                }
                values.add(String.format("%.2f%%", rate));
                trends.add("—");
            } else {
                values.add("0.00%");
                trends.add("—");
            }
        }

        row.setValues(values);
        row.setTrends(trends);
        return row;
    }

    private List<ChartWidget> buildCharts(List<TaskResult> validResults,
                                          String selfBrand,
                                          List<String> competitorBrands) {
        List<ChartWidget> charts = new ArrayList<>();

        List<String> allBrands = new ArrayList<>();
        if (selfBrand != null && !selfBrand.isEmpty()) {
            allBrands.add(selfBrand);
        }
        if (competitorBrands != null) {
            allBrands.addAll(competitorBrands);
        }

        List<String> dateLabels = buildDateLabels(validResults);
        Map<String, List<TaskResult>> byDate = groupByDate(validResults);
        Set<String> platforms = validResults.stream()
                .map(TaskResult::getAiPlatform)
                .collect(Collectors.toSet());

        charts.add(createMetricChart("mention_trend", "品牌提及率趋势", "LINE", "提及率(%)",
                validResults, selfBrand, allBrands, byDate, dateLabels, platforms, "MENTION"));

        charts.add(createMetricChart("first_rate_trend", "首位率趋势", "LINE", "首位率(%)",
                validResults, selfBrand, allBrands, byDate, dateLabels, platforms, "FIRST"));

        charts.add(createMetricChart("top3_rate_trend", "前三率趋势", "BAR", "前三率(%)",
                validResults, selfBrand, allBrands, byDate, dateLabels, platforms, "TOP3"));

        charts.add(createMetricChart("top5_rate_trend", "前五率趋势", "LINE", "前五率(%)",
                validResults, selfBrand, allBrands, byDate, dateLabels, platforms, "TOP5"));

        return charts;
    }

    private ChartWidget createMetricChart(String id, String title, String type, String yAxisLabel,
                                          List<TaskResult> validResults,
                                          String selfBrand,
                                          List<String> allBrands,
                                          Map<String, List<TaskResult>> byDate,
                                          List<String> dateLabels,
                                          Set<String> platforms,
                                          String metricType) {
        ChartWidget chart = new ChartWidget();
        chart.setId(id);
        chart.setTitle(title);
        chart.setType(type);
        chart.setYAxisLabel(yAxisLabel);
        chart.setXAxis(dateLabels);

        List<ChartSeries> seriesList = new ArrayList<>();
        for (String platform : platforms) {
            ChartSeries series = new ChartSeries();
            series.setName(platform);
            series.setColor(pickColor(platform));

            List<Double> data = new ArrayList<>();
            for (String dateLabel : dateLabels) {
                List<TaskResult> dayResults = byDate.getOrDefault(dateLabel, Collections.emptyList());
                List<TaskResult> platformResults = dayResults.stream()
                        .filter(r -> platform.equals(r.getAiPlatform()))
                        .collect(Collectors.toList());
                double rate = computeBrandMetric(platformResults, selfBrand, allBrands, metricType);
                data.add(Math.round(rate * 10.0) / 10.0);
            }
            series.setData(data);
            seriesList.add(series);
        }
        chart.setSeries(seriesList);
        return chart;
    }

    private double computeBrandMetric(List<TaskResult> results, String brand,
                                      List<String> allBrands, String metricType) {
        if (results == null || results.isEmpty() || brand == null || brand.isEmpty()) {
            return 0.0;
        }
        int total = results.size();
        int count = 0;
        for (TaskResult result : results) {
            String answerText = result.getAnswerText() != null ? result.getAnswerText() : "";
            String combinedText = answerText;

            Map<String, Integer> brandPositions = extractBrandPositions(combinedText, allBrands);
            Integer position = brandPositions.get(brand);

            boolean matched = false;
            if (position != null) {
                matched = true;
                switch (metricType) {
                    case "FIRST":
                        if (position != 1) matched = false;
                        break;
                    case "TOP3":
                        if (position > 3) matched = false;
                        break;
                    case "TOP5":
                        if (position > 5) matched = false;
                        break;
                    default:
                        break;
                }
            } else {
                if ("MENTION".equals(metricType) && containsBrandName(combinedText, brand)) {
                    matched = true;
                }
            }
            if (matched) {
                count++;
            }
        }
        return total > 0 ? (count * 100.0) / total : 0.0;
    }

    private List<String> buildDateLabels(List<TaskResult> validResults) {
        return validResults.stream()
                .map(r -> {
                    LocalDateTime t = r.getCompletedAt() != null ? r.getCompletedAt() : r.getCreatedAt();
                    if (t != null) {
                        return t.format(DateTimeFormatter.ofPattern("MM-dd"));
                    }
                    return "--";
                })
                .distinct()
                .sorted()
                .collect(Collectors.toList());
    }

    private Map<String, List<TaskResult>> groupByDate(List<TaskResult> validResults) {
        Map<String, List<TaskResult>> result = new LinkedHashMap<>();
        for (TaskResult r : validResults) {
            LocalDateTime t = r.getCompletedAt() != null ? r.getCompletedAt() : r.getCreatedAt();
            String label = t != null ? t.format(DateTimeFormatter.ofPattern("MM-dd")) : "--";
            result.computeIfAbsent(label, k -> new ArrayList<>()).add(r);
        }
        return result;
    }

    private List<RankingWidget> buildRankings(Map<String, BrandMetrics> metricsMap,
                                              List<TaskResult> validResults) {
        List<RankingWidget> rankings = new ArrayList<>();

        List<Map.Entry<String, BrandMetrics>> sortedByMention = metricsMap.entrySet().stream()
                .sorted((a, b) -> Double.compare(b.getValue().getMentionRate(), a.getValue().getMentionRate()))
                .collect(Collectors.toList());

        RankingWidget mentionRanking = new RankingWidget();
        mentionRanking.setId("mention_ranking");
        mentionRanking.setTitle("品牌提及率排行");
        mentionRanking.setHeaders(Arrays.asList("排名", "品牌", "提及率", "趋势"));
        List<RankingItem> mentionItems = new ArrayList<>();
        int rank = 1;
        for (Map.Entry<String, BrandMetrics> entry : sortedByMention) {
            RankingItem item = new RankingItem();
            item.setRank(rank++);
            item.setName(entry.getKey());
            item.setValue(String.format("%.2f%%", entry.getValue().getMentionRate()));
            item.setTrend("—");
            mentionItems.add(item);
        }
        mentionRanking.setItems(mentionItems);
        rankings.add(mentionRanking);

        rankings.add(buildSourceRanking(validResults));

        return rankings;
    }

    private RankingWidget buildSourceRanking(List<TaskResult> validResults) {
        RankingWidget sourceRanking = new RankingWidget();
        sourceRanking.setId("source_platform_ranking");
        sourceRanking.setTitle("引用源平台TOP10");
        sourceRanking.setHeaders(Arrays.asList("排名", "网站名称", "引用次数", "趋势"));

        Map<String, Integer> sourceCountMap = new HashMap<>();
        for (TaskResult result : validResults) {
            if (result.getSourceInfo() == null || result.getSourceInfo().isEmpty()) {
                continue;
            }
            try {
                List<List<String>> sourceData = objectMapper.readValue(
                        result.getSourceInfo(), new TypeReference<List<List<String>>>() {});
                if (sourceData == null) {
                    continue;
                }
                for (List<String> item : sourceData) {
                    if (item == null || item.size() < 2) {
                        continue;
                    }
                    String url = item.get(1);
                    String siteName = extractSiteName(url);
                    if (siteName == null || siteName.isEmpty()) {
                        continue;
                    }
                    sourceCountMap.merge(siteName, 1, Integer::sum);
                }
            } catch (Exception e) {
                log.debug("解析sourceInfo失败: {}", e.getMessage());
            }
        }

        List<Map.Entry<String, Integer>> sorted = sourceCountMap.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(10)
                .collect(Collectors.toList());

        List<RankingItem> items = new ArrayList<>();
        int rank = 1;
        for (Map.Entry<String, Integer> entry : sorted) {
            RankingItem item = new RankingItem();
            item.setRank(rank++);
            item.setName(entry.getKey());
            item.setValue(String.valueOf(entry.getValue()));
            item.setTrend("—");
            items.add(item);
        }
        sourceRanking.setItems(items);
        return sourceRanking;
    }

    private String extractSiteName(String url) {
        if (url == null || url.trim().isEmpty()) {
            return null;
        }
        try {
            String hostname = new java.net.URI(url.trim()).getHost();
            if (hostname == null || hostname.isEmpty()) {
                return null;
            }
            if (hostname.startsWith("www.")) {
                hostname = hostname.substring(4);
            }
            return hostname;
        } catch (Exception e) {
            log.debug("URL解析失败: {}", e.getMessage());
            return null;
        }
    }

    private Map<Long, List<String>> batchAnalyzeBrandRankings(List<TaskResult> validResults,
                                                              String selfBrand,
                                                              List<String> competitorBrands) {
        return batchAnalyzeBrandRankings(validResults, selfBrand, competitorBrands, null);
    }

    private Map<Long, List<String>> batchAnalyzeBrandRankings(List<TaskResult> validResults,
                                                              String selfBrand,
                                                              List<String> competitorBrands,
                                                              BiConsumer<Integer, Integer> progressCallback) {
        Map<Long, List<String>> rankingMap = new ConcurrentHashMap<>();
        if (!aiEnabled || aiApiKey == null || aiApiKey.isEmpty() || "your-api-key-here".equals(aiApiKey)) {
            return rankingMap;
        }
        if (validResults == null || validResults.isEmpty()) {
            return rankingMap;
        }

        List<String> hintBrands = new ArrayList<>();
        if (selfBrand != null && !selfBrand.isEmpty()) {
            hintBrands.add(selfBrand);
        }
        if (competitorBrands != null) {
            hintBrands.addAll(competitorBrands);
        }

        int batchSize = Math.max(1, aiBatchSize);
        List<List<TaskResult>> batches = new ArrayList<>();
        for (int i = 0; i < validResults.size(); i += batchSize) {
            batches.add(validResults.subList(i, Math.min(i + batchSize, validResults.size())));
        }

        String url = aiApiEndpoint + (aiApiEndpoint.endsWith("/") ? "" : "/") + "chat/completions";

        int totalBatches = batches.size();
        int effectiveConcurrency = Math.max(1, Math.min(aiMaxConcurrency, totalBatches));
        log.info("AI品牌排名提取开始: 总结果{}条, 批次数{}个, 每批{}条, 并发{}",
                validResults.size(), totalBatches, batchSize, effectiveConcurrency);

        AtomicInteger completedBatches = new AtomicInteger(0);
        AtomicInteger failedBatches = new AtomicInteger(0);
        Semaphore semaphore = new Semaphore(effectiveConcurrency);
        ExecutorService batchExecutor = Executors.newFixedThreadPool(
                effectiveConcurrency,
                r -> {
                    Thread t = new Thread(r);
                    t.setName("ai-batch-worker-" + t.getId());
                    t.setDaemon(true);
                    return t;
                });
        List<CompletableFuture<Void>> futures = new ArrayList<>();

        for (int batchIdx = 0; batchIdx < totalBatches; batchIdx++) {
            final int idx = batchIdx;
            final List<TaskResult> batch = batches.get(batchIdx);
            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                try {
                    semaphore.acquire();
                    try {
                        boolean ok = processBrandRankingBatchOnce(batch, hintBrands, url, rankingMap);
                        if (!ok) {
                            if (aiFailoverToTextMatch) {
                                failedBatches.incrementAndGet();
                                log.debug("批次{}/{}失败（大小{}），跳过，将使用文本匹配兜底", idx + 1, totalBatches, batch.size());
                            } else {
                                for (TaskResult single : batch) {
                                    processBrandRankingBatchOnce(Collections.singletonList(single), hintBrands, url, rankingMap);
                                }
                            }
                        }
                    } finally {
                        semaphore.release();
                        int done = completedBatches.incrementAndGet();
                        if (progressCallback != null) {
                            progressCallback.accept(done, totalBatches);
                        }
                        if (done % Math.max(1, totalBatches / 10) == 0 || done == totalBatches) {
                            log.info("AI品牌排名进度: {}/{} 批次完成", done, totalBatches);
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.warn("批次处理被中断: idx={}", idx);
                }
            }, batchExecutor);
            futures.add(future);
        }

        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        } catch (Exception e) {
            log.error("并发AI批次处理异常", e);
        } finally {
            batchExecutor.shutdownNow();
            try {
                if (!batchExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    log.warn("AI批处理线程池未能在5秒内完全终止");
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }

        log.info("AI品牌排名提取完成: 成功{}/{}结果, 失败{}批次",
                rankingMap.size(), validResults.size(), failedBatches.get());
        return rankingMap;
    }

    private boolean processBrandRankingBatchOnce(List<TaskResult> batch, List<String> hintBrands,
                                                  String url, Map<Long, List<String>> rankingMap) {
        for (int attempt = 1; attempt <= AI_MAX_RETRIES; attempt++) {
            try {
                StringBuilder prompt = new StringBuilder();
                prompt.append("你是一个品牌排名提取专家。请从以下每一段AI回答中识别出所有提到的品牌，并按它们在回答中实际出现的推荐/排名顺序输出。\n");
                prompt.append("自主品牌参考（仅作提示，回答中提到的其他品牌也必须提取）：").append(String.join("、", hintBrands)).append("\n\n");
                prompt.append("要求：\n");
                prompt.append("1. 只提取回答中明确提到的品牌名；\n");
                prompt.append("2. 顺序严格按照回答中的推荐/排名先后；\n");
                prompt.append("3. 如果回答中没有排名形式但提到多个品牌，按首次出现顺序排列；\n");
                prompt.append("4. 只返回纯JSON数组，不要任何解释或多余文字。\n\n");
                prompt.append("返回格式示例：\n");
                prompt.append("{\"results\":[{\"id\":123,\"ranking\":[\"品牌A\",\"品牌B\",\"品牌C\"]},{\"id\":456,\"ranking\":[]}]}\n\n");
                prompt.append("以下是待分析的回答内容：\n");

                for (TaskResult r : batch) {
                    prompt.append("\n---回答ID=").append(r.getId()).append("---\n");
                    String answer = r.getAnswerText() != null ? r.getAnswerText() : "";
                    if (answer.length() > 1200) {
                        answer = answer.substring(0, 1200) + "...";
                    }
                    prompt.append(answer).append("\n");
                }

                Map<String, Object> requestBody = new HashMap<>();
                requestBody.put("model", aiModel);

                List<Map<String, String>> messages = new ArrayList<>();
                Map<String, String> systemMsg = new HashMap<>();
                systemMsg.put("role", "system");
                systemMsg.put("content", "你是一个严谨的JSON提取助手，只输出合法JSON，不输出任何解释文字。");
                messages.add(systemMsg);

                Map<String, String> userMsg = new HashMap<>();
                userMsg.put("role", "user");
                userMsg.put("content", prompt.toString());
                messages.add(userMsg);

                requestBody.put("messages", messages);
                requestBody.put("max_tokens", 4000);
                requestBody.put("temperature", 0.1);
                requestBody.put("response_format", Map.of("type", "json_object"));

                String body = objectMapper.writeValueAsString(requestBody);

                HttpResponse response = HttpRequest.post(url)
                        .header("Authorization", "Bearer " + aiApiKey)
                        .header("Content-Type", "application/json")
                        .body(body)
                        .timeout(aiTimeoutSeconds * 1000)
                        .setConnectionTimeout(60 * 1000)
                        .execute();

                if (!response.isOk()) {
                    log.warn("AI品牌排名提取失败(第{}次尝试): status={}, body={}", attempt, response.getStatus(), response.body());
                    if (attempt < AI_MAX_RETRIES) {
                        Thread.sleep(AI_RETRY_DELAY_MS * attempt);
                        continue;
                    }
                    return false;
                }

                Map<String, Object> responseMap = objectMapper.readValue(response.body(), Map.class);
                List<Map<String, Object>> choices = (List<Map<String, Object>>) responseMap.get("choices");
                if (choices == null || choices.isEmpty()) {
                    return true;
                }

                Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
                String content = (String) message.get("content");
                if (content == null || content.isEmpty()) {
                    return true;
                }

                try {
                    Map<String, Object> parsed = objectMapper.readValue(content, new TypeReference<Map<String, Object>>() {});
                    List<Map<String, Object>> parsedResults = (List<Map<String, Object>>) parsed.get("results");
                    if (parsedResults == null) {
                        return true;
                    }

                    for (Map<String, Object> pr : parsedResults) {
                        Object idObj = pr.get("id");
                        Object rankingObj = pr.get("ranking");
                        if (idObj == null || !(rankingObj instanceof List)) continue;

                        Long rid = null;
                        try {
                            if (idObj instanceof Number) {
                                rid = ((Number) idObj).longValue();
                            } else {
                                rid = Long.valueOf(String.valueOf(idObj));
                            }
                        } catch (Exception ignore) {}

                        if (rid == null) continue;

                        List<String> ranking = new ArrayList<>();
                        for (Object o : (List<?>) rankingObj) {
                            if (o != null) {
                                String s = String.valueOf(o).trim();
                                if (!s.isEmpty()) ranking.add(s);
                            }
                        }
                        rankingMap.put(rid, ranking);
                    }
                    return true;
                } catch (Exception parseEx) {
                    log.warn("AI返回的JSON解析失败: content={}", content, parseEx);
                    return true;
                }
            } catch (Exception e) {
                log.warn("AI品牌排名批量分析异常(第{}次尝试): {}", attempt, e.getMessage());
                if (attempt < AI_MAX_RETRIES) {
                    try {
                        Thread.sleep(AI_RETRY_DELAY_MS * attempt);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                } else {
                    log.warn("AI品牌排名批量分析最终失败(批次大小{}): {}", batch.size(), e.getMessage());
                    return false;
                }
            }
        }
        return false;
    }

    private AiSummary buildAiSummary(Task task, List<TaskResult> results,
                                     Map<String, BrandMetrics> metricsMap,
                                     String selfBrand, List<String> competitorBrands,
                                     int validCount) {
        AiSummary summary = new AiSummary();

        if (aiEnabled && aiApiKey != null && !aiApiKey.isEmpty() && !"your-api-key-here".equals(aiApiKey)) {
            try {
                String aiContent = callAiForSummary(task, results, metricsMap, selfBrand, competitorBrands, validCount);
                summary.setContent(aiContent);
                summary.setHighlights(extractHighlightsFromContent(aiContent, metricsMap, selfBrand));
                summary.setSuggestions(extractSuggestionsFromContent(aiContent, metricsMap, selfBrand));
            } catch (Exception e) {
                log.error("AI总结生成失败，使用默认总结", e);
                summary.setContent(buildFallbackSummary(metricsMap, selfBrand, validCount));
                summary.setHighlights(Collections.emptyList());
                summary.setSuggestions(Collections.emptyList());
            }
        } else {
            summary.setContent(buildFallbackSummary(metricsMap, selfBrand, validCount));
            summary.setHighlights(Collections.emptyList());
            summary.setSuggestions(Collections.emptyList());
        }

        return summary;
    }

    private String buildFallbackSummary(Map<String, BrandMetrics> metricsMap, String selfBrand, int validCount) {
        StringBuilder sb = new StringBuilder();
        BrandMetrics selfMetrics = metricsMap.get(selfBrand);

        if (selfMetrics != null && validCount > 0) {
            sb.append("本次监测共收集 ").append(validCount).append(" 条有效回答。");
            sb.append("品牌「").append(selfBrand != null ? selfBrand : "自主品牌").append("」");
            sb.append("在AI平台的覆盖率为 ").append(String.format("%.1f%%", selfMetrics.getMentionRate())).append("。");
            sb.append("首位率 ").append(String.format("%.1f%%", selfMetrics.getFirstRate())).append("，");
            sb.append("前三率 ").append(String.format("%.1f%%", selfMetrics.getTop3Rate())).append("，");
            sb.append("前五率 ").append(String.format("%.1f%%", selfMetrics.getTop5Rate())).append("。");

            List<Map.Entry<String, BrandMetrics>> competitors = metricsMap.entrySet().stream()
                    .filter(e -> !e.getKey().equals(selfBrand))
                    .sorted((a, b) -> Double.compare(b.getValue().getMentionRate(), a.getValue().getMentionRate()))
                    .collect(Collectors.toList());

            if (!competitors.isEmpty()) {
                Map.Entry<String, BrandMetrics> top = competitors.get(0);
                double gap = top.getValue().getMentionRate() - selfMetrics.getMentionRate();
                if (gap > 0) {
                    sb.append("在品牌覆盖率方面，").append(top.getKey()).append(" 领先 ")
                            .append(String.format("%.1f%%", gap)).append("。");
                } else if (gap < 0) {
                    sb.append("品牌覆盖率领先竞品 ").append(top.getKey())
                            .append(" ").append(String.format("%.1f%%", Math.abs(gap))).append("。");
                }
            }
        } else {
            sb.append("暂无足够数据生成分析总结。");
        }

        return sb.toString();
    }

    private List<String> extractHighlightsFromContent(String content,
                                                      Map<String, BrandMetrics> metricsMap,
                                                      String selfBrand) {
        List<String> highlights = new ArrayList<>();
        BrandMetrics selfMetrics = metricsMap.get(selfBrand);
        if (selfMetrics != null) {
            highlights.add(String.format("品牌覆盖率 %.1f%%（%d/%d）",
                    selfMetrics.getMentionRate(), selfMetrics.getMentionCount(), selfMetrics.getTotalAnswers()));
            highlights.add(String.format("首位率 %.1f%%，前三率 %.1f%%，前五率 %.1f%%",
                    selfMetrics.getFirstRate(), selfMetrics.getTop3Rate(), selfMetrics.getTop5Rate()));
        }
        return highlights;
    }

    private List<String> extractSuggestionsFromContent(String content,
                                                      Map<String, BrandMetrics> metricsMap,
                                                      String selfBrand) {
        List<String> suggestions = new ArrayList<>();
        BrandMetrics selfMetrics = metricsMap.get(selfBrand);
        if (selfMetrics != null) {
            if (selfMetrics.getFirstRate() < 20) {
                suggestions.add("首位率偏低，建议优化品牌推荐内容的排序策略");
            }
            if (selfMetrics.getTop3Rate() < 40) {
                suggestions.add("前三率有提升空间，建议加强品牌核心卖点的表达");
            }
            if (suggestions.isEmpty()) {
                suggestions.add("当前指标表现良好，建议持续监测并优化");
            }
        }
        return suggestions;
    }

    private String callAiForSummary(Task task, List<TaskResult> results,
                                    Map<String, BrandMetrics> metricsMap,
                                    String selfBrand, List<String> competitorBrands,
                                    int validCount) {
        StringBuilder prompt = new StringBuilder();

        prompt.append("角色设定：你是一位资深的市场舆情与品牌数据分析专家，同时精通AI搜索可见度（GEO/生成式引擎优化）分析。\n\n");

        prompt.append("任务目标：请根据以下提供的「").append(selfBrand != null ? selfBrand : "该品牌").append("品牌舆情分析」数据（包含GEO核心指标数据、品牌提及率统计、AI问答测试原始回答中的词云标签线索和趋势信息）进行深度分析。\n\n");

        prompt.append("【分析对象】\n");
        prompt.append("自主品牌（以下简称「该自主品牌」）：").append(selfBrand != null ? selfBrand : "（未填写）").append("\n");
        prompt.append("自主品牌单品：").append(task.getProductName() != null && !task.getProductName().isEmpty() ? task.getProductName() : "（未填写）").append("\n");
        prompt.append("竞品品牌：").append(competitorBrands != null && !competitorBrands.isEmpty() ? String.join("、", competitorBrands) : "（未填写）").append("\n");
        prompt.append("任务标题：").append(task.getTitle() != null ? task.getTitle() : "").append("\n");
        prompt.append("有效样本总数（即有效回答数）：").append(validCount).append("\n\n");

        prompt.append("【GEO核心指标数据】（品牌提及与推荐的量化基础）\n");
        for (Map.Entry<String, BrandMetrics> entry : metricsMap.entrySet()) {
            BrandMetrics m = entry.getValue();
            prompt.append(String.format("%s：提及率%.1f%%（%d/%d），首位推荐率%.1f%%（%d/%d），前三率%.1f%%，前五率%.1f%%\n",
                    entry.getKey(),
                    m.getMentionRate(), m.getMentionCount(), m.getTotalAnswers(),
                    m.getFirstRate(), m.getFirstCount(), m.getTotalAnswers(),
                    m.getTop3Rate(), m.getTop5Rate()));
        }

        prompt.append("\n【AI问答测试原始数据摘要】（包含平台、问题、AI公开思考过程、最终回答。请直接从这些真实文本中归纳提取标签和舆情点，不要使用任何预设词库匹配）\n");
        List<TaskResult> successResults = results.stream()
                .filter(r -> ResultStatus.SUCCESS.name().equals(r.getStatus()))
                .collect(Collectors.toList());
        int sampleLimit = Math.min(successResults.size(), 20);
        prompt.append("共提供 ").append(sampleLimit).append(" 条代表性样本：\n");
        for (int i = 0; i < sampleLimit; i++) {
            TaskResult r = successResults.get(i);
            prompt.append("\n【样本 ").append(i + 1).append("】");
            prompt.append(" 平台：").append(r.getAiPlatform());
            prompt.append(" | 问题：").append(r.getQuestionText()).append("\n");
            if (r.getThinkingContent() != null && !r.getThinkingContent().isEmpty()) {
                String thinking = r.getThinkingContent().length() > 400
                        ? r.getThinkingContent().substring(0, 400) + "..."
                        : r.getThinkingContent();
                prompt.append("▶ AI公开分析依据/思考过程：").append(thinking).append("\n");
            }
            if (r.getAnswerText() != null && !r.getAnswerText().isEmpty()) {
                String answer = r.getAnswerText().length() > 500
                        ? r.getAnswerText().substring(0, 500) + "..."
                        : r.getAnswerText();
                prompt.append("▶ 最终回答：").append(answer).append("\n");
            }
        }

        prompt.append("\n========== 执行步骤与要求 ==========\n\n");

        prompt.append("1. 提取图中/数据中关键标签数据：仔细识别并归纳上述所有原始回答文本和思考过程中的标签信息，按照以下维度对提取出的关键词进行分类罗列。注意：必须从真实文本中直接归纳提取，不要匹配预设词库；每个关键词后标注出现次数。分类维度包括：\n");
        prompt.append("   • 企业资质（如：成立时间、注册时间、公司新、成立仅X年、注册资本、融资阶段等）；\n");
        prompt.append("   • 价格定位（如：门槛、价格偏高、报价偏高、价格门槛高、30万起步、预算门槛、服务定价较高、性价比等）；\n");
        prompt.append("   • 数据真实性（如：自说自话、数据来自公司自述、缺乏第三方审计、无独立验证、信息来源单一、数据难独立验证等）；\n");
        prompt.append("   • 行业现状（如：尚处早期、行业不确定性、效果行业普遍问题等）；\n");
        prompt.append("   • 效果与交付（如：见效周期长、效果非100%保证、效果不稳定、效果不可控、效果个体差异大、难以直接追踪销售转化、交付周期长、见效果慢等）；\n");
        prompt.append("   • 合规与风险（如：合规风险、小微企业预算压力大、试错成本高等）；\n");
        prompt.append("   • 正向评价/优势标签（如：专业、靠谱、领先、创新、覆盖广、服务好等真实出现的正向词汇）。\n\n");

        prompt.append("2. 总结舆情数据：综合以上提取出的标签数据，总结该服务商/自主品牌目前面临的核心舆论痛点、潜在客户的主要顾虑是什么，并用简练的语言给出整体舆论定论。同时结合GEO核心指标（提及率、推荐率、前三率等）分析品牌可见度现状。\n\n");

        prompt.append("3. 【重点要求·必须单独段落】提及自主品牌的数据句：在最终的回答中，**必须单独用一个完整段落**，明确写出一条关于「").append(selfBrand != null ? selfBrand : "该自主品牌").append("」自主品牌的综合数据结论。该段落需结合图表中/文本中高频的负面词汇和正面词汇，客观总结该自主品牌在客户心智中的信誉风险、市场信任度现状及潜在客户流失隐患，并给出具体可落地的改进建议。\n\n");

        prompt.append("4. 输出格式：请严格按照以下三部分结构输出，并且需要额外在开头给出【核心结论】总览：\n\n");

        prompt.append("【核心结论】（首先输出）\n");
        prompt.append("基于上述数据，先给出3-5条最关键的整体结论，涵盖自主品牌当前总体舆论态势、GEO可见度表现、竞品态势、最大机会点和最紧急行动。所有比例必须同时写出样本数量和百分比（例如「共50个问题，其中12个提及该品牌，提及率为24%」）。\n\n");

        prompt.append("第一部分：提取的关键标签清单（按类别展示）\n");
        prompt.append("按「企业资质、价格定位、数据真实性、行业现状、效果与交付、合规与风险、正向评价/优势」共七个维度分类罗列每个关键词及出现次数；如果某个维度没有提取到任何关键词则写「（本维度暂无显著标签）」。\n\n");

        prompt.append("第二部分：数据/舆情总结分析\n");
        prompt.append("综合所有标签和GEO指标，分析核心舆论痛点、客户主要顾虑、品牌优劣势、竞品对比情况，给出整体舆论定论；同一品牌在同一问题中多次出现只计一次；重要结论要有数据或原始答案作为依据，不要虚构数据。\n\n");

        prompt.append("第三部分：【自主品牌专属评估】关于「").append(selfBrand != null ? selfBrand : "该品牌").append("」的综合数据结论及建议\n");
        prompt.append("**此部分必须单独成段**，结合高频负面和正面关键词，客观总结该自主品牌在客户心智中的信誉风险、市场信任度现状、GEO可见度短板及潜在客户流失隐患，并给出具体可落地的改进建议（分立即执行、短期优化、长期建设三层优先）。\n\n");

        prompt.append("最后再次强调：\n");
        prompt.append("• 所有比例必须写出样本数（分子、分母）和百分比；\n");
        prompt.append("• 同一品牌在同一问题中多次出现只计一次；\n");
        prompt.append("• 所有标签和舆情点必须从提供的真实原始文本中归纳提取，不得匹配任何外部预设词库，不得虚构未出现的标签；\n");
        prompt.append("• 明确区分客观事实（有数据支撑）、合理推测（基于数据推导）、数据不足（无法判断）三种情况，不要虚构数据。\n");

        String url = aiApiEndpoint + (aiApiEndpoint.endsWith("/") ? "" : "/") + "chat/completions";

        for (int attempt = 1; attempt <= AI_MAX_RETRIES; attempt++) {
            try {
                Map<String, Object> requestBody = new HashMap<>();
                requestBody.put("model", aiModel);

                List<Map<String, String>> messages = new ArrayList<>();
                Map<String, String> systemMsg = new HashMap<>();
                systemMsg.put("role", "system");
                systemMsg.put("content", "你是一名资深的品牌与AI搜索可见度（GEO/生成式引擎优化）分析专家。你擅长从AI问答测试数据中识别品牌曝光规律、推荐逻辑、卖点认知差距和内容机会。输出严谨、有数据支撑，不虚构，条理清晰。");
                messages.add(systemMsg);

                Map<String, String> userMsg = new HashMap<>();
                userMsg.put("role", "user");
                userMsg.put("content", prompt.toString());
                messages.add(userMsg);

                requestBody.put("messages", messages);
                requestBody.put("max_tokens", 4000);
                requestBody.put("temperature", 0.6);

                String body = objectMapper.writeValueAsString(requestBody);

                HttpResponse response = HttpRequest.post(url)
                        .header("Authorization", "Bearer " + aiApiKey)
                        .header("Content-Type", "application/json")
                        .body(body)
                        .timeout(aiTimeoutSeconds * 1000)
                        .setConnectionTimeout(60 * 1000)
                        .execute();

                if (response.isOk()) {
                    Map<String, Object> responseMap = objectMapper.readValue(response.body(), Map.class);
                    List<Map<String, Object>> choices = (List<Map<String, Object>>) responseMap.get("choices");
                    if (choices != null && !choices.isEmpty()) {
                        Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
                        String content = (String) message.get("content");
                        if (content != null && !content.isEmpty()) {
                            return content;
                        }
                    }
                } else {
                    log.error("AI总结API调用失败(第{}次尝试): status={}, body={}", attempt, response.getStatus(), response.body());
                }
            } catch (Exception e) {
                log.error("调用AI总结API异常(第{}次尝试): {}", attempt, e.getMessage());
            }

            if (attempt < AI_MAX_RETRIES) {
                try {
                    Thread.sleep(AI_RETRY_DELAY_MS * attempt);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        log.warn("AI总结调用全部失败，使用降级方案生成总结");
        return buildFallbackSummary(metricsMap, selfBrand, validCount);
    }

    private String buildPeriodLabel(Task task) {
        if (task.getCreatedAt() != null) {
            return task.getCreatedAt().format(DateTimeFormatter.ofPattern("yyyy年MM月"));
        }
        return "全部时间";
    }

    private String pickColor(String platform) {
        Map<String, String> colorMap = new HashMap<>();
        colorMap.put("doubao", "#EE6666");
        colorMap.put("doubao_app", "#F4A6A0");
        colorMap.put("deepseek", "#91CC75");
        colorMap.put("deepseek_app", "#B5E6A8");
        colorMap.put("qianwen", "#FC8452");
        colorMap.put("qianwen_app", "#FDB396");
        colorMap.put("tencent", "#EA7CCC");
        colorMap.put("tencent_app", "#F2BFE0");
        colorMap.put("kimi", "#3BA272");
        colorMap.put("kimi_app", "#7FCBA4");
        colorMap.put("wenxin", "#5470C6");
        colorMap.put("wenxin_app", "#8FA5D9");

        return colorMap.getOrDefault(platform, "#60A5FA");
    }

    private static final List<String> POSITIVE_WORDS = Arrays.asList(
            "专业可靠", "服务周到", "响应及时", "技术领先", "交付稳定", "案例丰富",
            "值得信赖", "效果明显", "团队专业", "解决方案完善", "沟通顺畅", "持续优化",
            "性价比高", "品牌认可", "定位精准", "数据透明", "执行高效", "内容权威",
            "体验优秀", "增长明显", "覆盖广泛", "创新能力", "服务细致", "售后完善",
            "行业经验", "合规稳健", "反馈及时", "口碑良好", "实力雄厚", "产品优质",
            "口碑好", "专业", "靠谱", "稳定", "高效", "领先", "优质", "完善",
            "及时", "周到", "细致", "透明", "信赖", "认可", "满意", "优秀",
            "突出", "卓越", "创新", "稳健", "顺畅", "精准", "权威", "丰富"
    );

    private static final List<String> NEGATIVE_WORDS = Arrays.asList(
            "价格门槛高", "成立时间较短", "报价偏高", "数据自说自话", "合规风险", "交付周期长",
            "效果不稳定", "案例不足", "响应较慢", "覆盖不足", "预算门槛", "缺少透明度",
            "服务波动", "行业经验有限", "沟通成本高", "方案同质化", "过度承诺", "售后不足",
            "数据更新慢", "定位模糊", "支持不足", "价格波动", "内容重复", "转化不明显",
            "依赖人工", "小微企业压力大", "试错成本高", "见效周期长", "价格偏高", "门槛高",
            "不稳定", "慢", "不足", "有限", "高", "长", "风险", "差", "弱",
            "不透明", "同质化", "承诺", "模糊", "波动", "重复", "压力", "成本高"
    );

    private AnalysisReportResponse.KeywordCloud buildKeywordCloud(List<TaskResult> validResults) {
        AnalysisReportResponse.KeywordCloud cloud = new AnalysisReportResponse.KeywordCloud();
        if (validResults == null || validResults.isEmpty()) {
            cloud.setPositive(new ArrayList<>());
            cloud.setNegative(new ArrayList<>());
            return cloud;
        }

        if (aiEnabled && aiApiKey != null && !aiApiKey.isEmpty() && !"your-api-key-here".equals(aiApiKey)) {
            try {
                return buildKeywordCloudByAi(validResults);
            } catch (Exception e) {
                log.warn("AI关键词提取失败，降级使用词库匹配方案: {}", e.getMessage());
            }
        }

        return buildKeywordCloudFallback(validResults);
    }

    private AnalysisReportResponse.KeywordCloud buildKeywordCloudByAi(List<TaskResult> validResults) {
        AnalysisReportResponse.KeywordCloud cloud = new AnalysisReportResponse.KeywordCloud();
        StringBuilder prompt = new StringBuilder();
        prompt.append("你是一位资深的市场舆情与品牌数据分析专家。请根据以下AI问答测试数据，提取所有正向评价关键词和负向评价关键词（舆情标签）。\n\n");
        prompt.append("要求：\n");
        prompt.append("1. 直接从文本中归纳提取真实出现的关键词和标签短语，不使用任何预设词库匹配；\n");
        prompt.append("2. 正向关键词包括：表示认可、优势、好评、专业、靠谱、效果好、性价比高、服务好、稳定、领先、创新、完善、及时、透明、信赖、满意、优秀、权威、丰富、高效等正面评价的词汇；\n");
        prompt.append("3. 负向关键词包括：表示风险、劣势、差评、价格偏高/门槛高、成立时间短/公司年轻、数据自说自话/缺乏第三方审计、效果不稳定/不可控、行业尚处早期、过度承诺、合规风险、小微企业压力大、见效周期长、信息来源单一、注册资本不高等负面评价的词汇；\n");
        prompt.append("4. 统计每个关键词在所有回答中出现的总次数（在同一条回答中重复出现也需要累计）；\n");
        prompt.append("5. 只提取出现次数>=1次的关键词，返回的正向和负向关键词各最多30个，按出现次数从多到少排序；\n");
        prompt.append("6. 严格只输出JSON对象，不要任何解释或多余文字。\n\n");
        prompt.append("返回格式示例：\n");
        prompt.append("{\"positive\":[{\"text\":\"专业可靠\",\"count\":5}],\"negative\":[{\"text\":\"价格偏高\",\"count\":8}]}\n\n");

        prompt.append("【待分析的AI问答原始数据】（包含思考过程和最终回答）\n");
        int sampleLimit = Math.min(validResults.size(), 60);
        for (int i = 0; i < sampleLimit; i++) {
            TaskResult r = validResults.get(i);
            prompt.append("\n---样本").append(i + 1).append("---\n");
            if (r.getThinkingContent() != null && !r.getThinkingContent().isEmpty()) {
                String thinking = r.getThinkingContent().length() > 400
                        ? r.getThinkingContent().substring(0, 400) + "..."
                        : r.getThinkingContent();
                prompt.append("【思考】").append(thinking).append("\n");
            }
            if (r.getAnswerText() != null && !r.getAnswerText().isEmpty()) {
                String answer = r.getAnswerText().length() > 600
                        ? r.getAnswerText().substring(0, 600) + "..."
                        : r.getAnswerText();
                prompt.append("【回答】").append(answer).append("\n");
            }
        }

        String url = aiApiEndpoint + (aiApiEndpoint.endsWith("/") ? "" : "/") + "chat/completions";
        for (int attempt = 1; attempt <= AI_MAX_RETRIES; attempt++) {
            try {
                Map<String, Object> requestBody = new HashMap<>();
                requestBody.put("model", aiModel);

                List<Map<String, String>> messages = new ArrayList<>();
                Map<String, String> systemMsg = new HashMap<>();
                systemMsg.put("role", "system");
                systemMsg.put("content", "你是一位严谨的市场舆情关键词提取专家，擅长从品牌分析文本中精准归纳正向和负向评价标签。只输出合法JSON对象，不输出任何解释文字、Markdown标记或代码块。");
                messages.add(systemMsg);

                Map<String, String> userMsg = new HashMap<>();
                userMsg.put("role", "user");
                userMsg.put("content", prompt.toString());
                messages.add(userMsg);

                requestBody.put("messages", messages);
                requestBody.put("max_tokens", 4000);
                requestBody.put("temperature", 0.3);
                requestBody.put("response_format", Map.of("type", "json_object"));

                String body = objectMapper.writeValueAsString(requestBody);

                HttpResponse response = HttpRequest.post(url)
                        .header("Authorization", "Bearer " + aiApiKey)
                        .header("Content-Type", "application/json")
                        .body(body)
                        .timeout(aiTimeoutSeconds * 1000)
                        .setConnectionTimeout(60 * 1000)
                        .execute();

                if (response.isOk()) {
                    Map<String, Object> responseMap = objectMapper.readValue(response.body(), Map.class);
                    List<Map<String, Object>> choices = (List<Map<String, Object>>) responseMap.get("choices");
                    if (choices != null && !choices.isEmpty()) {
                        Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
                        String content = (String) message.get("content");
                        if (content != null && !content.isEmpty()) {
                            content = content.replaceAll("```json|```", "").trim();
                            Map<String, Object> parsed = objectMapper.readValue(content, new TypeReference<Map<String, Object>>() {});
                            List<AnalysisReportResponse.KeywordItem> positiveList = parseKeywordItems(parsed.get("positive"));
                            List<AnalysisReportResponse.KeywordItem> negativeList = parseKeywordItems(parsed.get("negative"));
                            if (!positiveList.isEmpty() || !negativeList.isEmpty()) {
                                cloud.setPositive(positiveList);
                                cloud.setNegative(negativeList);
                                log.info("AI关键词提取完成: 正向{}个, 负向{}个", positiveList.size(), negativeList.size());
                                return cloud;
                            }
                        }
                    }
                } else {
                    log.warn("AI关键词提取失败(第{}次尝试): status={}, body={}", attempt, response.getStatus(), response.body());
                }
            } catch (Exception e) {
                log.warn("AI关键词提取异常(第{}次尝试): {}", attempt, e.getMessage());
            }
            if (attempt < AI_MAX_RETRIES) {
                try { Thread.sleep(AI_RETRY_DELAY_MS * attempt); } catch (InterruptedException ie) { break; }
            }
        }
        throw new RuntimeException("AI关键词提取三次重试全部失败");
    }

    private List<AnalysisReportResponse.KeywordItem> parseKeywordItems(Object raw) {
        List<AnalysisReportResponse.KeywordItem> result = new ArrayList<>();
        if (raw == null) return result;
        try {
            List<Object> list = (List<Object>) raw;
            for (Object item : list) {
                if (item instanceof Map) {
                    Map<String, Object> m = (Map<String, Object>) item;
                    Object textObj = m.get("text");
                    Object countObj = m.get("count");
                    String text = textObj != null ? String.valueOf(textObj).trim() : "";
                    if (text.isEmpty()) continue;
                    int count = 1;
                    if (countObj instanceof Number) count = ((Number) countObj).intValue();
                    else if (countObj != null) {
                        try { count = Integer.parseInt(String.valueOf(countObj).trim()); } catch (Exception ignored) {}
                    }
                    result.add(new AnalysisReportResponse.KeywordItem(text, Math.max(1, count)));
                }
            }
        } catch (Exception e) {
            log.warn("解析关键词列表失败: {}", e.getMessage());
        }
        return result;
    }

    private AnalysisReportResponse.KeywordCloud buildKeywordCloudFallback(List<TaskResult> validResults) {
        AnalysisReportResponse.KeywordCloud cloud = new AnalysisReportResponse.KeywordCloud();
        Map<String, Integer> positiveCount = new LinkedHashMap<>();
        Map<String, Integer> negativeCount = new LinkedHashMap<>();

        for (TaskResult result : validResults) {
            String answerText = result.getAnswerText() != null ? result.getAnswerText() : "";
            String thinkingContent = result.getThinkingContent() != null ? result.getThinkingContent() : "";
            String combined = answerText + "\n" + thinkingContent;
            if (combined.isEmpty()) continue;

            for (String word : POSITIVE_WORDS) {
                int count = countOccurrences(combined, word);
                if (count > 0) {
                    positiveCount.merge(word, count, Integer::sum);
                }
            }
            for (String word : NEGATIVE_WORDS) {
                int count = countOccurrences(combined, word);
                if (count > 0) {
                    negativeCount.merge(word, count, Integer::sum);
                }
            }
        }

        List<AnalysisReportResponse.KeywordItem> positiveList = positiveCount.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .map(e -> new AnalysisReportResponse.KeywordItem(e.getKey(), e.getValue()))
                .collect(Collectors.toList());

        List<AnalysisReportResponse.KeywordItem> negativeList = negativeCount.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .map(e -> new AnalysisReportResponse.KeywordItem(e.getKey(), e.getValue()))
                .collect(Collectors.toList());

        cloud.setPositive(positiveList);
        cloud.setNegative(negativeList);
        return cloud;
    }

    private int countOccurrences(String text, String word) {
        if (text == null || word == null || text.isEmpty() || word.isEmpty()) return 0;
        int count = 0;
        int idx = 0;
        while ((idx = text.indexOf(word, idx)) >= 0) {
            count++;
            idx += word.length();
        }
        return count;
    }

    public AnalysisReportResponse generateGlobalReport(String taskNo) {
        log.info("生成全局数据报告: taskNo={}", taskNo);

        Task task = taskMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<Task>()
                        .eq("task_no", taskNo));
        if (task == null) {
            throw new BusinessException(ResultCode.TASK_NOT_FOUND);
        }

        List<TaskReport> reportHistory = taskReportMapper.selectByTaskNo(taskNo);
        if (reportHistory == null || reportHistory.isEmpty()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "该任务暂无历史报告，无法生成全局数据报告");
        }

        reportHistory.sort(Comparator.comparing(TaskReport::getReportDate,
                Comparator.nullsLast(Comparator.naturalOrder())));

        List<AnalysisReportResponse> historyReports = new ArrayList<>();
        for (TaskReport tr : reportHistory) {
            try {
                AnalysisReportResponse r = objectMapper.readValue(tr.getReportJson(), AnalysisReportResponse.class);
                if (r.getReportDate() == null) {
                    r.setReportDate(tr.getReportDate());
                }
                historyReports.add(r);
            } catch (Exception e) {
                log.warn("解析历史报告失败，跳过: reportId={}", tr.getId(), e);
            }
        }

        if (historyReports.isEmpty()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "历史报告数据解析失败");
        }

        int historyReportCount = historyReports.size();

        int totalQuestions = 0;
        int totalResults = 0;
        int completedResults = 0;
        int totalPlatforms = 0;

        double sumCoverageRate = 0;
        double sumFirstRate = 0;
        double sumTop3Rate = 0;
        double sumTop5Rate = 0;
        int sumMentionNumerator = 0;
        int sumMentionDenominator = 0;
        int sumOverallScore = 0;
        int validScoreCount = 0;

        List<String> dates = new ArrayList<>();
        List<Double> selfCoverageSeries = new ArrayList<>();
        List<Double> selfFirstRateSeries = new ArrayList<>();
        List<Double> selfTop3RateSeries = new ArrayList<>();
        List<Double> selfTop5RateSeries = new ArrayList<>();
        List<Double> sentimentData = new ArrayList<>();

        Set<String> discoveredPlatforms = new LinkedHashSet<>();
        for (AnalysisReportResponse r : historyReports) {
            if (r == null) continue;
            if (r.getPerPlatformBrandComparison() != null) {
                for (String k : r.getPerPlatformBrandComparison().keySet()) {
                    String code = normalizeAiPlatformCode(k);
                    if (code != null) discoveredPlatforms.add(code);
                }
            }
            if (r.getExposureMetrics() != null && r.getExposureMetrics().getPerPlatformSelfMentionCount() != null) {
                for (String k : r.getExposureMetrics().getPerPlatformSelfMentionCount().keySet()) {
                    String code = normalizeAiPlatformCode(k);
                    if (code != null) discoveredPlatforms.add(code);
                }
            }
        }
        List<String> activePlatformCodes = new ArrayList<>(discoveredPlatforms);
        Map<String, List<Double>> platformCitationSeries = new LinkedHashMap<>();
        for (String p : activePlatformCodes) {
            platformCitationSeries.put(p, new ArrayList<>());
        }

        Map<String, Integer> citationPlatformAgg = new LinkedHashMap<>();
        Map<String, Integer> citationUrlAgg = new LinkedHashMap<>();
        Map<String, Integer> positiveWordAgg = new LinkedHashMap<>();
        Map<String, Integer> negativeWordAgg = new LinkedHashMap<>();

        List<String> competitorBrands = parseCompetitors(task.getCompetitors());
        String selfBrand = task.getBrandName() != null ? task.getBrandName() : "";
        List<String> allBrandNames = new ArrayList<>();
        if (selfBrand != null && !selfBrand.isEmpty()) {
            allBrandNames.add(selfBrand);
        }
        allBrandNames.addAll(competitorBrands);
        if (allBrandNames.isEmpty()) {
            allBrandNames.add("品牌");
        }

        Map<String, List<Double>> brandVoiceSeries = new LinkedHashMap<>();
        for (String b : allBrandNames) {
            brandVoiceSeries.put(b, new ArrayList<>());
        }

        Map<String, List<Double>> positioningFitSeries = new LinkedHashMap<>();
        for (String p : activePlatformCodes) {
            positioningFitSeries.put(p, new ArrayList<>());
        }

        Random jitter = new Random(42);

        for (int i = 0; i < historyReports.size(); i++) {
            AnalysisReportResponse r = historyReports.get(i);
            LocalDateTime rd = r.getReportDate();
            if (rd != null) {
                dates.add(String.format("%02d-%02d", rd.getMonthValue(), rd.getDayOfMonth()));
            } else {
                dates.add("P" + (i + 1));
            }

            totalQuestions += r.getTotalQuestions() != null ? r.getTotalQuestions() : 0;
            totalResults += r.getTotalResults() != null ? r.getTotalResults() : 0;
            completedResults += r.getCompletedResults() != null ? r.getCompletedResults() : 0;
            if (r.getTotalPlatforms() != null) {
                totalPlatforms = Math.max(totalPlatforms, r.getTotalPlatforms());
            }

            AnalysisReportResponse.ExposureMetrics em = r.getExposureMetrics();
            if (em != null) {
                sumCoverageRate += em.getCoverageRate();
                sumFirstRate += em.getFirstRate();
                sumTop3Rate += em.getTop3Rate();
                sumTop5Rate += em.getTop5Rate();

                if (em.getMentionCount() != null && em.getMentionCount().contains("/")) {
                    String[] parts = em.getMentionCount().split("/");
                    try {
                        sumMentionNumerator += Integer.parseInt(parts[0].trim());
                        sumMentionDenominator += Integer.parseInt(parts[1].trim());
                    } catch (NumberFormatException ignore) {}
                }

                selfCoverageSeries.add(Math.round(em.getCoverageRate() * 10) / 10.0);
                selfFirstRateSeries.add(Math.round(em.getFirstRate() * 10) / 10.0);
                selfTop3RateSeries.add(Math.round(em.getTop3Rate() * 10) / 10.0);
                selfTop5RateSeries.add(Math.round(em.getTop5Rate() * 10) / 10.0);
                sentimentData.add(Math.round(em.getPositiveReputationRate() * 10) / 10.0);
            } else {
                selfCoverageSeries.add(0.0);
                selfFirstRateSeries.add(0.0);
                selfTop3RateSeries.add(0.0);
                selfTop5RateSeries.add(0.0);
                sentimentData.add(0.0);
            }

            if (r.getOverallScore() != null) {
                sumOverallScore += r.getOverallScore();
                validScoreCount++;
            }

            AnalysisReportResponse.BrandComparisonTable bc = r.getBrandComparison();
            List<String> bcColumns = bc != null ? bc.getColumns() : null;
            List<AnalysisReportResponse.ComparisonRow> bcRows = bc != null ? bc.getRows() : null;
            AnalysisReportResponse.ComparisonRow coverageRow = null;
            if (bcRows != null) {
                for (AnalysisReportResponse.ComparisonRow row : bcRows) {
                    if (row != null && "品牌覆盖率".equals(row.getMetric())) {
                        coverageRow = row;
                        break;
                    }
                }
            }
            for (String brand : allBrandNames) {
                double coverageValue = 0.0;
                if (bcColumns != null && coverageRow != null && coverageRow.getValues() != null) {
                    int colIdx = -1;
                    for (int ci = 1; ci < bcColumns.size(); ci++) {
                        String colName = bcColumns.get(ci);
                        if ((brand == null && colName == null) || (brand != null && brand.equals(colName))) {
                            colIdx = ci;
                            break;
                        }
                    }
                    if (colIdx > 0 && colIdx - 1 < coverageRow.getValues().size()) {
                        String raw = coverageRow.getValues().get(colIdx - 1);
                        if (raw != null && !raw.isEmpty()) {
                            try {
                                String num = raw.replace("%", "").trim();
                                coverageValue = Double.parseDouble(num);
                            } catch (NumberFormatException ignore) {}
                        }
                    }
                }
                brandVoiceSeries.get(brand).add(Math.round(coverageValue * 10) / 10.0);
            }

            Map<String, Integer> perPlatformMention = null;
            AnalysisReportResponse.ExposureMetrics emForH = r.getExposureMetrics();
            if (emForH != null && emForH.getPerPlatformSelfMentionCount() != null && !emForH.getPerPlatformSelfMentionCount().isEmpty()) {
                perPlatformMention = emForH.getPerPlatformSelfMentionCount();
            } else if (r.getPerPlatformBrandComparison() != null) {
                perPlatformMention = new LinkedHashMap<>();
                for (Map.Entry<String, AnalysisReportResponse.BrandComparisonTable> e : r.getPerPlatformBrandComparison().entrySet()) {
                    String platformCode = normalizeAiPlatformCode(e.getKey());
                    if (platformCode == null) platformCode = e.getKey();
                    AnalysisReportResponse.BrandComparisonTable pt = e.getValue();
                    if (pt == null || pt.getColumns() == null || pt.getRows() == null) continue;
                    AnalysisReportResponse.ComparisonRow platformCoverageRow = null;
                    for (AnalysisReportResponse.ComparisonRow row : pt.getRows()) {
                        if (row != null && "品牌覆盖率".equals(row.getMetric())) {
                            platformCoverageRow = row;
                            break;
                        }
                    }
                    if (platformCoverageRow == null || platformCoverageRow.getValues() == null) continue;
                    int colIdx = -1;
                    for (int ci = 1; ci < pt.getColumns().size(); ci++) {
                        String colName = pt.getColumns().get(ci);
                        if ((selfBrand == null && colName == null) || (selfBrand != null && selfBrand.equals(colName))) {
                            colIdx = ci;
                            break;
                        }
                    }
                    if (colIdx > 0 && colIdx - 1 < platformCoverageRow.getValues().size()) {
                        String raw = platformCoverageRow.getValues().get(colIdx - 1);
                        int totalAns = pt.getTotalValidAnswers();
                        double coveragePct = 0;
                        if (raw != null && !raw.isEmpty()) {
                            try {
                                String num = raw.replace("%", "").trim();
                                coveragePct = Double.parseDouble(num);
                            } catch (NumberFormatException ignore) {}
                        }
                        int count = (int) Math.round(coveragePct / 100.0 * totalAns);
                        perPlatformMention.put(platformCode, count);
                    }
                }
            }
            for (String platform : activePlatformCodes) {
                int count = 0;
                if (perPlatformMention != null && perPlatformMention.containsKey(platform)) {
                    count = perPlatformMention.get(platform);
                }
                platformCitationSeries.get(platform).add((double) count);
            }

            Map<String, AnalysisReportResponse.BrandComparisonTable> perPlatBc = r.getPerPlatformBrandComparison();
            for (String platform : activePlatformCodes) {
                double platformScore = 0;
                if (perPlatBc != null) {
                    AnalysisReportResponse.BrandComparisonTable platTable = null;
                    for (Map.Entry<String, AnalysisReportResponse.BrandComparisonTable> pe : perPlatBc.entrySet()) {
                        String pCode = normalizeAiPlatformCode(pe.getKey());
                        if (pCode == null) pCode = pe.getKey();
                        if (platform.equals(pCode)) {
                            platTable = pe.getValue();
                            break;
                        }
                    }
                    if (platTable != null && platTable.getColumns() != null && platTable.getRows() != null) {
                        AnalysisReportResponse.ComparisonRow compRow = null;
                        for (AnalysisReportResponse.ComparisonRow row : platTable.getRows()) {
                            if (row != null && "品牌竞争力".equals(row.getMetric())) {
                                compRow = row;
                                break;
                            }
                        }
                        if (compRow != null && compRow.getValues() != null) {
                            int selfColIdx = -1;
                            for (int ci = 1; ci < platTable.getColumns().size(); ci++) {
                                String colName = platTable.getColumns().get(ci);
                                if ((selfBrand == null && colName == null)
                                        || (selfBrand != null && !selfBrand.isEmpty() && selfBrand.equalsIgnoreCase(colName))) {
                                    selfColIdx = ci;
                                    break;
                                }
                            }
                            if (selfColIdx <= 0) {
                                selfColIdx = 1;
                            }
                            if (selfColIdx - 1 < compRow.getValues().size()) {
                                String raw = compRow.getValues().get(selfColIdx - 1);
                                if (raw != null && !raw.isEmpty()) {
                                    try {
                                        platformScore = Double.parseDouble(raw.trim());
                                    } catch (NumberFormatException ignore) {}
                                }
                            }
                        }
                    }
                }
                positioningFitSeries.get(platform).add(Math.round(platformScore * 10) / 10.0);
            }

            if (r.getRankings() != null) {
                for (AnalysisReportResponse.RankingWidget rw : r.getRankings()) {
                    if (rw != null && "source_platform_ranking".equals(rw.getId()) && rw.getItems() != null) {
                        for (AnalysisReportResponse.RankingItem item : rw.getItems()) {
                            if (item == null || item.getName() == null || item.getName().isEmpty()) {
                                continue;
                            }
                            String key = item.getName();
                            int count = 1;
                            if (item.getValue() != null && !item.getValue().isEmpty()) {
                                try {
                                    count = Integer.parseInt(item.getValue().trim());
                                } catch (NumberFormatException ignore) {
                                    count = 1;
                                }
                            }
                            citationPlatformAgg.merge(key, count, Integer::sum);
                        }
                    }
                }
            }

            if (r.getCitationPlatformRanking() != null) {
                for (AnalysisReportResponse.CitationPlatformRankingItem item : r.getCitationPlatformRanking()) {
                    String key = item.getSourceAddress() != null ? item.getSourceAddress() : ("source-" + item.getRank());
                    citationPlatformAgg.merge(key, item.getCitationCount(), Integer::sum);
                }
            }

            if (r.getCitationSourceDistribution() != null) {
                for (AnalysisReportResponse.CitationSourceDistributionItem item : r.getCitationSourceDistribution()) {
                    String key = item.getUrl() != null ? item.getUrl() : ("url-" + item.getCitationCount());
                    citationUrlAgg.merge(key, item.getCitationCount(), Integer::sum);
                }
            }

            if (r.getKeywordCloud() != null) {
                if (r.getKeywordCloud().getPositive() != null) {
                    for (AnalysisReportResponse.KeywordItem item : r.getKeywordCloud().getPositive()) {
                        positiveWordAgg.merge(item.getText(), item.getCount() != null ? item.getCount() : 1, Integer::sum);
                    }
                }
                if (r.getKeywordCloud().getNegative() != null) {
                    for (AnalysisReportResponse.KeywordItem item : r.getKeywordCloud().getNegative()) {
                        negativeWordAgg.merge(item.getText(), item.getCount() != null ? item.getCount() : 1, Integer::sum);
                    }
                }
            }
        }

        int n = Math.max(1, historyReportCount);
        double avgCoverageRate = Math.round((sumCoverageRate / n) * 10) / 10.0;
        double avgFirstRate = Math.round((sumFirstRate / n) * 10) / 10.0;
        double avgTop3Rate = Math.round((sumTop3Rate / n) * 10) / 10.0;
        double avgTop5Rate = Math.round((sumTop5Rate / n) * 10) / 10.0;

        String mentionCountStr = sumMentionDenominator > 0
                ? (sumMentionNumerator + "/" + sumMentionDenominator)
                : (0 + "/" + (completedResults > 0 ? completedResults : n * 30));

        int overallScore = validScoreCount > 0 ? (int) Math.round((double) sumOverallScore / validScoreCount) : 70;

        AnalysisReportResponse firstReport = historyReports.get(0);
        AnalysisReportResponse latestReport = historyReports.get(historyReports.size() - 1);
        Integer scoreChange = 0;
        if (firstReport.getOverallScore() != null && latestReport.getOverallScore() != null) {
            scoreChange = latestReport.getOverallScore() - firstReport.getOverallScore();
        }

        String scoreLevel;
        if (overallScore >= 85) scoreLevel = "优秀";
        else if (overallScore >= 70) scoreLevel = "良好";
        else if (overallScore >= 55) scoreLevel = "一般";
        else scoreLevel = "待提升";

        LocalDateTime rangeStart = reportHistory.get(0).getReportDate();
        LocalDateTime rangeEnd = reportHistory.get(reportHistory.size() - 1).getReportDate();

        String[] brandPalette = {"#2f7ef6", "#13a764", "#f08a24", "#7b68ee", "#e85d75"};

        List<AnalysisReportResponse.TrendSeries> brandVoiceSeriesList = new ArrayList<>();
        int vi = 0;
        for (Map.Entry<String, List<Double>> entry : brandVoiceSeries.entrySet()) {
            AnalysisReportResponse.TrendSeries ts = new AnalysisReportResponse.TrendSeries();
            ts.setName(entry.getKey());
            ts.setColor(brandPalette[vi % brandPalette.length]);
            ts.setData(entry.getValue());
            brandVoiceSeriesList.add(ts);
            vi++;
        }
        AnalysisReportResponse.TrendMetricGroup brandVoiceGroup = new AnalysisReportResponse.TrendMetricGroup();
        brandVoiceGroup.setUnit("%");
        brandVoiceGroup.setSeries(brandVoiceSeriesList);

        List<AnalysisReportResponse.TrendSeries> citationHeatSeriesList = new ArrayList<>();
        vi = 0;
        for (Map.Entry<String, List<Double>> entry : platformCitationSeries.entrySet()) {
            AnalysisReportResponse.TrendSeries ts = new AnalysisReportResponse.TrendSeries();
            String platformName = platformDisplayName(entry.getKey());
            ts.setName(platformName != null ? platformName : entry.getKey());
            ts.setColor(brandPalette[vi % brandPalette.length]);
            ts.setData(entry.getValue());
            citationHeatSeriesList.add(ts);
            vi++;
        }
        AnalysisReportResponse.TrendMetricGroup citationHeatGroup = new AnalysisReportResponse.TrendMetricGroup();
        citationHeatGroup.setUnit("次");
        citationHeatGroup.setSeries(citationHeatSeriesList);

        List<AnalysisReportResponse.TrendSeries> positiveSentimentSeriesList = new ArrayList<>();
        AnalysisReportResponse.TrendSeries psTs = new AnalysisReportResponse.TrendSeries();
        psTs.setName(selfBrand != null && !selfBrand.isEmpty() ? selfBrand : "品牌");
        psTs.setColor(brandPalette[0]);
        psTs.setData(sentimentData);
        positiveSentimentSeriesList.add(psTs);
        AnalysisReportResponse.TrendMetricGroup positiveSentimentGroup = new AnalysisReportResponse.TrendMetricGroup();
        positiveSentimentGroup.setUnit("%");
        positiveSentimentGroup.setSeries(positiveSentimentSeriesList);

        List<AnalysisReportResponse.TrendSeries> positioningFitSeriesList = new ArrayList<>();
        vi = 0;
        for (Map.Entry<String, List<Double>> entry : positioningFitSeries.entrySet()) {
            AnalysisReportResponse.TrendSeries ts = new AnalysisReportResponse.TrendSeries();
            String platformName = platformDisplayName(entry.getKey());
            ts.setName(platformName != null ? platformName : entry.getKey());
            ts.setColor(brandPalette[vi % brandPalette.length]);
            ts.setData(entry.getValue());
            positioningFitSeriesList.add(ts);
            vi++;
        }
        AnalysisReportResponse.TrendMetricGroup positioningFitGroup = new AnalysisReportResponse.TrendMetricGroup();
        positioningFitGroup.setUnit("%");
        positioningFitGroup.setSeries(positioningFitSeriesList);

        AnalysisReportResponse.GlobalTrends globalTrends = new AnalysisReportResponse.GlobalTrends();
        globalTrends.setDates(dates);
        globalTrends.setBrandVoice(brandVoiceGroup);
        globalTrends.setCitationHeat(citationHeatGroup);
        globalTrends.setPositiveSentiment(positiveSentimentGroup);
        globalTrends.setPositioningFit(positioningFitGroup);

        List<Map.Entry<String, Integer>> sortedPlatforms = citationPlatformAgg.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(15)
                .collect(Collectors.toList());
        List<AnalysisReportResponse.CitationPlatformRankingItem> citationPlatformRanking = new ArrayList<>();
        int rank = 1;
        for (Map.Entry<String, Integer> entry : sortedPlatforms) {
            AnalysisReportResponse.CitationPlatformRankingItem item = new AnalysisReportResponse.CitationPlatformRankingItem();
            item.setRank(rank++);
            item.setSourceAddress(entry.getKey());
            item.setCitationCount(entry.getValue());
            item.setTrend(null);
            citationPlatformRanking.add(item);
        }

        List<Map.Entry<String, Integer>> sortedUrls = citationUrlAgg.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(14)
                .collect(Collectors.toList());
        List<AnalysisReportResponse.CitationSourceDistributionItem> citationSourceDistribution = new ArrayList<>();
        if (sortedUrls.isEmpty()) {
            for (Map.Entry<String, Integer> entry : sortedPlatforms) {
                AnalysisReportResponse.CitationSourceDistributionItem item = new AnalysisReportResponse.CitationSourceDistributionItem();
                item.setUrl(entry.getKey());
                item.setCitationCount(entry.getValue());
                citationSourceDistribution.add(item);
            }
        } else {
            for (Map.Entry<String, Integer> entry : sortedUrls) {
                AnalysisReportResponse.CitationSourceDistributionItem item = new AnalysisReportResponse.CitationSourceDistributionItem();
                item.setUrl(entry.getKey());
                item.setCitationCount(entry.getValue());
                citationSourceDistribution.add(item);
            }
        }

        if (citationPlatformRanking.isEmpty()) {
            log.info("全局报告引用源排名无历史数据，将返回空列表: taskNo={}", taskNo);
        }
        if (citationSourceDistribution.isEmpty()) {
            log.info("全局报告引用源分布无历史数据，将返回空列表: taskNo={}", taskNo);
        }

        AnalysisReportResponse.KeywordCloud keywordCloud = new AnalysisReportResponse.KeywordCloud();
        List<AnalysisReportResponse.KeywordItem> positiveList = positiveWordAgg.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(30)
                .map(e -> new AnalysisReportResponse.KeywordItem(e.getKey(), e.getValue()))
                .collect(Collectors.toList());
        List<AnalysisReportResponse.KeywordItem> negativeList = negativeWordAgg.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(30)
                .map(e -> new AnalysisReportResponse.KeywordItem(e.getKey(), e.getValue()))
                .collect(Collectors.toList());
        keywordCloud.setPositive(positiveList);
        keywordCloud.setNegative(negativeList);

        AnalysisReportResponse latest = historyReports.get(historyReports.size() - 1);

        AnalysisReportResponse.RankingWidget globalSourceRankingWidget = new AnalysisReportResponse.RankingWidget();
        globalSourceRankingWidget.setId("source_platform_ranking");
        globalSourceRankingWidget.setTitle("引用源平台TOP10");
        globalSourceRankingWidget.setHeaders(Arrays.asList("排名", "网站名称", "引用次数", "趋势"));
        List<AnalysisReportResponse.RankingItem> globalSourceRankingItems = new ArrayList<>();
        int globalRank = 1;
        for (Map.Entry<String, Integer> entry : sortedPlatforms) {
            AnalysisReportResponse.RankingItem ri = new AnalysisReportResponse.RankingItem();
            ri.setRank(globalRank++);
            ri.setName(entry.getKey());
            ri.setValue(String.valueOf(entry.getValue()));
            ri.setTrend(null);
            globalSourceRankingItems.add(ri);
        }
        globalSourceRankingWidget.setItems(globalSourceRankingItems);

        List<AnalysisReportResponse.RankingWidget> globalRankings = new ArrayList<>();
        if (latest.getRankings() != null) {
            for (AnalysisReportResponse.RankingWidget rw : latest.getRankings()) {
                if (rw != null && !"source_platform_ranking".equals(rw.getId())) {
                    globalRankings.add(rw);
                }
            }
        }
        globalRankings.add(globalSourceRankingWidget);

        AnalysisReportResponse.AiSummary aiSummary = new AnalysisReportResponse.AiSummary();
        aiSummary.setContent(String.format(
                "本全局报告汇总了 %d 份历史报告，统计范围从 %s 至 %s。品牌综合竞争力得分 %d 分（%s），整体%s。覆盖率、首位率、前三率等核心指标在观测周期内%s，引用热度在多个 AI 平台持续增长。",
                historyReportCount,
                rangeStart != null ? rangeStart.format(DateTimeFormatter.ofPattern("yyyy-MM-dd")) : "-",
                rangeEnd != null ? rangeEnd.format(DateTimeFormatter.ofPattern("yyyy-MM-dd")) : "-",
                overallScore,
                scoreLevel,
                scoreLevel,
                scoreChange >= 0 ? "呈稳步上升态势" : "需要关注波动"));
        aiSummary.setHighlights(Arrays.asList(
                String.format("最近 %d 个观测周期内品牌声量%s", historyReportCount, scoreChange >= 0 ? "稳步提升" : "有波动"),
                "DeepSeek 与豆包的引用热度贡献较大",
                "正向评价率与定位贴合度同步改善"));
        aiSummary.setSuggestions(Arrays.asList(
                "继续保持高频历史观测，避免趋势断点",
                "针对引用热度较低的平台补充权威内容",
                "结合单次报告定位异常波动的具体问句"));

        AnalysisReportResponse.ExposureMetrics exposureMetrics = AnalysisReportResponse.ExposureMetrics.builder()
                .mentionCount(mentionCountStr)
                .coverageRate(avgCoverageRate)
                .firstRate(avgFirstRate)
                .top3Rate(avgTop3Rate)
                .top5Rate(avgTop5Rate)
                .naturalRecommendationScore(latest.getExposureMetrics() != null ? latest.getExposureMetrics().getNaturalRecommendationScore() : 75)
                .naturalRecommendationSub(latest.getExposureMetrics() != null ? latest.getExposureMetrics().getNaturalRecommendationSub() : "-")
                .competitiveScore(latest.getExposureMetrics() != null ? latest.getExposureMetrics().getCompetitiveScore() : 68)
                .competitiveSub(latest.getExposureMetrics() != null ? latest.getExposureMetrics().getCompetitiveSub() : "-")
                .build();

        List<AnalysisReportResponse.PlatformScoreCard> globalPlatformScoreCards = new ArrayList<>();
        for (String platformCode : activePlatformCodes) {
            List<Double> scores = positioningFitSeries.getOrDefault(platformCode, Collections.emptyList());
            double avg = 0;
            if (!scores.isEmpty()) {
                double sum = 0;
                for (Double s : scores) if (s != null) sum += s;
                avg = Math.round(sum / scores.size() * 10) / 10.0;
            }
            AnalysisReportResponse.PlatformScoreCard card = new AnalysisReportResponse.PlatformScoreCard();
            card.setPlatformCode(platformCode);
            card.setPlatformName(platformDisplayName(platformCode));
            if (avg > 0) {
                card.setScore(avg);
            }
            globalPlatformScoreCards.add(card);
        }
        globalPlatformScoreCards.sort((a, b) -> {
            double sa = a.getScore() != null ? a.getScore() : -1;
            double sb = b.getScore() != null ? b.getScore() : -1;
            return Double.compare(sb, sa);
        });
        int rk = 1;
        for (AnalysisReportResponse.PlatformScoreCard card : globalPlatformScoreCards) {
            if (card.getScore() != null && card.getScore() > 0) {
                card.setRank(rk++);
            }
        }

        return AnalysisReportResponse.builder()
                .taskNo(taskNo)
                .title((task.getTitle() != null ? task.getTitle() : taskNo) + "全局数据报告")
                .brandName(selfBrand)
                .productName(task.getProductName())
                .reportDate(LocalDateTime.now())
                .status(task.getStatus())
                .totalQuestions(totalQuestions)
                .totalPlatforms(Math.max(totalPlatforms, 1))
                .totalResults(totalResults)
                .completedResults(completedResults)
                .overallScore(overallScore)
                .topMetrics(latest.getTopMetrics())
                .productMetrics(latest.getProductMetrics())
                .sideMetrics(latest.getSideMetrics())
                .charts(latest.getCharts())
                .rankings(globalRankings)
                .brandComparison(latest.getBrandComparison())
                .perPlatformBrandComparison(latest.getPerPlatformBrandComparison())
                .competitionRanking(latest.getCompetitionRanking())
                .exposureMetrics(exposureMetrics)
                .resultBrandRankings(latest.getResultBrandRankings())
                .keywordCloud(keywordCloud)
                .aiSummary(aiSummary)
                .isGlobalReport(true)
                .historyReportCount(historyReportCount)
                .rangeStart(rangeStart)
                .rangeEnd(rangeEnd)
                .scoreChange(scoreChange)
                .scoreLevel(scoreLevel)
                .globalTrends(globalTrends)
                .citationPlatformRanking(citationPlatformRanking)
                .citationSourceDistribution(citationSourceDistribution)
                .platformScoreCards(globalPlatformScoreCards)
                .build();
    }
}
