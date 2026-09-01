package com.geo.service;

import com.geo.common.BusinessException;
import com.geo.common.ResultCode;
import com.geo.common.SentimentUtils;
import com.geo.dto.TaskProgressVO;
import com.geo.dto.TaskRankingVO;
import com.geo.dto.TaskResultVO;
import com.geo.entity.Task;
import com.geo.entity.TaskAi;
import com.geo.entity.TaskQuestion;
import com.geo.entity.TaskResult;
import com.geo.enums.AiPlatform;
import com.geo.enums.ResultStatus;
import com.geo.enums.TaskStatus;
import com.geo.mapper.TaskAiMapper;
import com.geo.mapper.TaskMapper;
import com.geo.mapper.TaskQuestionMapper;
import com.geo.mapper.TaskResultMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.util.*;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
public class TaskService {

    private static final Logger log = LoggerFactory.getLogger(TaskService.class);

    private final ConcurrentHashMap<String, TaskRankingVO> rankingCache = new ConcurrentHashMap<>();

    private final TaskMapper taskMapper;
    private final TaskAiMapper taskAiMapper;
    private final TaskQuestionMapper taskQuestionMapper;
    private final TaskResultMapper taskResultMapper;
    private final ObjectMapper objectMapper;
    private final TaskScheduleService taskScheduleService;
    private final RestTemplate restTemplate;

    @Value("${geo.rpa.url:http://localhost:8084/internal/rpa/dispatch}")
    private String rpaDispatchUrl;

    public TaskService(TaskMapper taskMapper, TaskAiMapper taskAiMapper,
                       TaskQuestionMapper taskQuestionMapper, TaskResultMapper taskResultMapper,
                       ObjectMapper objectMapper,
                       @Lazy TaskScheduleService taskScheduleService,
                       RestTemplate restTemplate) {
        this.taskMapper = taskMapper;
        this.taskAiMapper = taskAiMapper;
        this.taskQuestionMapper = taskQuestionMapper;
        this.taskResultMapper = taskResultMapper;
        this.objectMapper = objectMapper;
        this.taskScheduleService = taskScheduleService;
        this.restTemplate = restTemplate;
    }

    private void dispatchToRpa(String taskNo, List<TaskResult> results, String brandName,
                               String productName, List<String> competitors,
                               String executionFrequency, Boolean retryOnFailure) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("taskNo", taskNo);
            body.put("results", results);
            body.put("brandName", brandName);
            body.put("productName", productName);
            body.put("competitors", competitors);
            body.put("executionFrequency", executionFrequency);
            body.put("retryOnFailure", retryOnFailure);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);

            ResponseEntity<String> response = restTemplate.postForEntity(
                    rpaDispatchUrl, request, String.class);

            if (!response.getStatusCode().is2xxSuccessful()) {
                log.error("调用RPA调度接口失败: taskNo={}, status={}, body={}",
                        taskNo, response.getStatusCode(), response.getBody());
            } else {
                log.debug("调用RPA调度接口成功: taskNo={}, resultCount={}", taskNo, results.size());
            }
        } catch (Exception e) {
            log.error("调用RPA调度接口异常: taskNo={}", taskNo, e);
        }
    }

    @Transactional
    public Task createTask(List<String> aiPlatforms, List<String> questions, String title,
                          String brandName, String productName, List<String> competitors,
                          String executionFrequency, Boolean retryOnFailure, String scope) {
        return createTask(aiPlatforms, questions, title, brandName, productName, competitors,
                executionFrequency, retryOnFailure, scope, null);
    }

    @Transactional
    public Task createTask(List<String> aiPlatforms, List<String> questions, String title,
                          String brandName, String productName, List<String> competitors,
                          String executionFrequency, Boolean retryOnFailure, String scope,
                          List<String> whitelistUrls) {
        validateAiPlatforms(aiPlatforms);
        validateQuestions(questions);
        validateBrandName(brandName);

        String taskNo = generateTaskNo();
        Task task = new Task();
        task.setTaskNo(taskNo);
        task.setUserId("anonymous");
        task.setTitle(title);
        task.setStatus(TaskStatus.PENDING.name());
        task.setTotalAiCount(aiPlatforms.size());
        task.setTotalQuestionCount(questions.size());
        task.setTotalCount(aiPlatforms.size() * questions.size());
        task.setCompletedCount(0);
        task.setFailedCount(0);
        task.setBrandName(brandName);
        task.setProductName(productName);
        task.setExecutionFrequency(executionFrequency != null ? executionFrequency : "single");
        task.setScope(scope != null ? scope : "LOCAL");
        task.setQuestionCount(questions.size());
        task.setIntentCount(0);
        try {
            task.setCompetitors(objectMapper.writeValueAsString(competitors));
        } catch (JsonProcessingException e) {
            log.warn("序列化竞争对手列表失败", e);
        }
        if (whitelistUrls != null && !whitelistUrls.isEmpty()) {
            try {
                task.setWhitelistUrls(objectMapper.writeValueAsString(whitelistUrls));
            } catch (JsonProcessingException e) {
                log.warn("序列化白名单URLs失败", e);
            }
        }

        taskMapper.insert(task);

        saveTaskAis(task.getId(), taskNo, aiPlatforms);
        List<TaskQuestion> savedQuestions = saveTaskQuestions(task.getId(), taskNo, questions);
        saveTaskCompetitors(task.getId(), taskNo, competitors);

        List<TaskResult> taskResults = createTaskResults(task.getId(), taskNo, aiPlatforms, savedQuestions, brandName);
        for (TaskResult result : taskResults) {
            taskResultMapper.insert(result);
        }

        return task;
    }

    @Transactional
    public Task submitTask(String taskNo, String executionFrequency, Boolean retryOnFailure) {
        Task task = getTaskByNo(taskNo);
        if (task == null) {
            throw new BusinessException(ResultCode.TASK_NOT_FOUND);
        }

        if (!TaskStatus.PENDING.name().equals(task.getStatus())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "任务状态不允许提交");
        }

        task.setStatus(TaskStatus.PROCESSING.name());
        task.setExecutionFrequency(executionFrequency != null ? executionFrequency : task.getExecutionFrequency());
        if (task.getExecutionFrequency() == null || task.getExecutionFrequency().isEmpty()) {
            task.setExecutionFrequency("single");
        }
        task.setNextRunTime(calculateNextRunTime(task.getExecutionFrequency()));
        taskMapper.updateById(task);
        taskScheduleService.scheduleTask(task);

        List<TaskResult> results = taskResultMapper.selectByTaskNo(taskNo);

        List<String> competitorsList = new ArrayList<>();
        if (task.getCompetitors() != null && !task.getCompetitors().isEmpty()) {
            try {
                competitorsList = objectMapper.readValue(task.getCompetitors(), new TypeReference<List<String>>() {});
            } catch (JsonProcessingException e) {
                log.warn("解析竞争对手列表失败", e);
            }
        }

        dispatchToRpa(taskNo, results, task.getBrandName(), task.getProductName(), competitorsList,
                executionFrequency, retryOnFailure);

        log.info("提交任务: taskNo={}", taskNo);
        return task;
    }

    @Transactional
    public void deleteTask(String taskNo) {
        Task task = getTaskByNo(taskNo);
        if (task == null) {
            throw new BusinessException(ResultCode.TASK_NOT_FOUND);
        }

        String status = task.getStatus();
        if (TaskStatus.PROCESSING.name().equals(status)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "任务正在运行中，无法删除");
        }

        taskScheduleService.cancelTask(taskNo);

        taskResultMapper.deleteByTaskNo(taskNo);
        taskQuestionMapper.deleteByTaskNo(taskNo);
        taskAiMapper.deleteByTaskNo(taskNo);
        taskMapper.deleteById(task.getId());

        log.info("删除任务: taskNo={}", taskNo);
    }

    public void rescheduleDueSingleTask(String taskNo) {
        Task task = getTaskByNo(taskNo);
        if (task == null) return;
        if (!isTaskDue(task)) return;
        if (TaskStatus.PROCESSING.name().equals(task.getStatus())) return;

        log.info("[精确调度] 触发周期任务: taskNo={}, nextRunTime={}, executionFrequency={}",
                taskNo, task.getNextRunTime(), task.getExecutionFrequency());
        try {
            task.setStatus(TaskStatus.PROCESSING.name());
            task.setCompletedAt(null);
            task.setNextRunTime(calculateNextRunTime(task.getExecutionFrequency()));
            taskMapper.updateById(task);
            taskScheduleService.scheduleTask(task);

            List<TaskResult> results = taskResultMapper.selectByTaskNo(taskNo);
            for (TaskResult result : results) {
                result.setStatus(ResultStatus.PENDING.name());
                result.setErrorMsg(null);
                result.setAnswerText(null);
                result.setScreenshotUrls(null);
                result.setDurationMs(null);
                result.setCompletedAt(null);
                result.setCreatedAt(LocalDateTime.now());
                result.setRpaRetryCount(result.getRpaRetryCount() != null ? result.getRpaRetryCount() + 1 : 0);
                taskResultMapper.updateById(result);
            }

            List<String> competitorsList = new ArrayList<>();
            if (task.getCompetitors() != null && !task.getCompetitors().isEmpty()) {
                try {
                    competitorsList = objectMapper.readValue(task.getCompetitors(), new TypeReference<List<String>>() {});
                } catch (JsonProcessingException e) {
                    log.warn("解析竞争对手列表失败", e);
                }
            }
            dispatchToRpa(taskNo, results, task.getBrandName(), task.getProductName(), competitorsList, "single", true);
        } catch (Exception e) {
            log.error("[精确调度] 触发周期任务失败: taskNo={}", taskNo, e);
        }
    }

    private void validateBrandName(String brandName) {
        if (brandName == null || brandName.trim().isEmpty()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "自主品牌名不能为空");
        }
        if (brandName.length() > 100) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "自主品牌名长度不能超过100字符");
        }
    }

    private void validateAiPlatforms(List<String> aiPlatforms) {
        if (aiPlatforms == null || aiPlatforms.isEmpty()) {
            throw new BusinessException(ResultCode.AI_LIST_EMPTY);
        }
        for (String platform : aiPlatforms) {
            if (AiPlatform.fromCode(platform) == null) {
                throw new BusinessException(ResultCode.AI_PLATFORM_INVALID, "不支持的AI平台: " + platform);
            }
        }
    }

    private void validateQuestions(List<String> questions) {
        if (questions == null || questions.isEmpty()) {
            throw new BusinessException(ResultCode.QUESTION_EMPTY);
        }
        if (questions.size() > 1000) {
            throw new BusinessException(ResultCode.QUESTION_TOO_MANY);
        }
        for (String question : questions) {
            if (question == null || question.trim().isEmpty()) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "问题不能为空");
            }
            if (question.length() > 1000) {
                throw new BusinessException(ResultCode.QUESTION_TOO_LONG);
            }
        }
    }

    private String generateTaskNo() {
        String timestamp = LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
        String uuid = UUID.randomUUID().toString().substring(0, 8);
        return "T" + timestamp + uuid.toUpperCase();
    }

    private void saveTaskAis(Long taskId, String taskNo, List<String> aiPlatforms) {
        for (String platform : aiPlatforms) {
            AiPlatform aiPlatform = AiPlatform.fromCode(platform);
            TaskAi taskAi = new TaskAi();
            taskAi.setTaskId(taskId);
            taskAi.setTaskNo(taskNo);
            taskAi.setAiPlatform(platform);
            taskAi.setAiDisplayName(aiPlatform != null ? aiPlatform.getDisplayName() : platform);
            taskAiMapper.insert(taskAi);
        }
    }

    private List<TaskQuestion> saveTaskQuestions(Long taskId, String taskNo, List<String> questions) {
        List<TaskQuestion> taskQuestions = new ArrayList<>();
        int order = 0;
        for (String question : questions) {
            TaskQuestion tq = new TaskQuestion();
            tq.setTaskId(taskId);
            tq.setTaskNo(taskNo);
            tq.setQuestionText(question);
            tq.setSortOrder(order++);
            taskQuestionMapper.insert(tq);
            taskQuestions.add(tq);
        }
        return taskQuestions;
    }

    private void saveTaskCompetitors(Long taskId, String taskNo, List<String> competitors) {
        if (competitors == null || competitors.isEmpty()) {
            return;
        }
        for (String competitor : competitors) {
            if (competitor != null && !competitor.trim().isEmpty()) {
                TaskQuestion tq = new TaskQuestion();
                tq.setTaskId(taskId);
                tq.setTaskNo(taskNo);
                tq.setQuestionText("竞争对手: " + competitor.trim());
                tq.setSortOrder(999);
                taskQuestionMapper.insert(tq);
            }
        }
    }

    private List<TaskResult> createTaskResults(Long taskId, String taskNo,
                                                List<String> aiPlatforms,
                                                List<TaskQuestion> questions,
                                                String brandName) {
        List<TaskResult> results = new ArrayList<>();
        for (TaskQuestion question : questions) {
            for (String platform : aiPlatforms) {
                TaskResult result = new TaskResult();
                result.setTaskId(taskId);
                result.setTaskNo(taskNo);
                result.setTaskQuestionId(question.getId());
                result.setAiPlatform(platform);
                result.setQuestionText(question.getQuestionText());
                result.setStatus(ResultStatus.PENDING.name());
                result.setRpaRetryCount(0);
                results.add(result);
            }
        }
        return results;
    }

    public Task getTaskByNo(String taskNo) {
        com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<Task> wrapper =
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<>();
        wrapper.eq("task_no", taskNo);
        return taskMapper.selectOne(wrapper);
    }

    @org.springframework.transaction.annotation.Transactional
    public Task getTaskByNoWithProgressRefresh(String taskNo) {
        Task task = getTaskByNo(taskNo);
        if (task == null) return null;
        try {
            syncTaskProgressFromResults(task);
        } catch (Exception e) {
            log.debug("刷新任务进度失败: taskNo={}", taskNo, e);
        }
        return task;
    }

    public List<Task> listTasks() {
        com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<Task> wrapper =
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<>();
        wrapper.orderByDesc("created_at");
        List<Task> tasks = taskMapper.selectList(wrapper);
        for (Task task : tasks) {
            if (task.getScope() == null || task.getScope().isEmpty()) {
                task.setScope("LOCAL");
            }
            if (task.getQuestionCount() == null) {
                task.setQuestionCount(task.getTotalQuestionCount() != null ? task.getTotalQuestionCount() : 0);
            }
            if (task.getIntentCount() == null) {
                task.setIntentCount(0);
            }
        }
        return tasks;
    }

    @org.springframework.transaction.annotation.Transactional
    public List<Task> listTasksWithProgressRefresh() {
        com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<Task> wrapper =
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<>();
        wrapper.orderByDesc("created_at");
        List<Task> tasks = taskMapper.selectList(wrapper);
        for (Task task : tasks) {
            String status = task.getStatus();
            boolean needSync = TaskStatus.PENDING.name().equals(status)
                    || TaskStatus.PROCESSING.name().equals(status);
            if (!needSync) {
                Integer cachedTotal = task.getTotalCount();
                Integer cachedCompleted = task.getCompletedCount();
                List<TaskResult> results = taskResultMapper.selectByTaskId(task.getId());
                int actualTotal = results.size();
                long actualCompleted = results.stream().filter(r ->
                        ResultStatus.SUCCESS.name().equals(r.getStatus())).count();
                if (cachedTotal == null || cachedTotal != actualTotal
                        || cachedCompleted == null || cachedCompleted.intValue() != actualCompleted) {
                    needSync = true;
                }
            }
            if (needSync) {
                try {
                    syncTaskProgressFromResults(task);
                } catch (Exception e) {
                    log.debug("刷新任务进度失败: taskNo={}", task.getTaskNo(), e);
                }
            }
            if (task.getScope() == null || task.getScope().isEmpty()) {
                task.setScope("LOCAL");
            }
            if (task.getQuestionCount() == null) {
                task.setQuestionCount(task.getTotalQuestionCount() != null ? task.getTotalQuestionCount() : 0);
            }
            if (task.getIntentCount() == null) {
                task.setIntentCount(0);
            }
        }
        return tasks;
    }

    private void syncTaskProgressFromResults(Task task) {
        if (task == null || task.getId() == null) return;
        List<TaskResult> results = taskResultMapper.selectByTaskId(task.getId());
        long successCount = 0;
        long failedCount = 0;
        long pendingCount = 0;
        for (TaskResult r : results) {
            String s = r.getStatus();
            if (ResultStatus.SUCCESS.name().equals(s)) {
                successCount++;
            } else if (ResultStatus.FAILED.name().equals(s) || ResultStatus.TIMEOUT.name().equals(s)) {
                failedCount++;
            } else {
                pendingCount++;
            }
        }
        task.setTotalCount(results.size());
        task.setCompletedCount((int) successCount);
        task.setFailedCount((int) failedCount);

        boolean statusChanged = false;
        if (pendingCount == 0) {
            String newStatus;
            if (failedCount == 0) {
                newStatus = TaskStatus.COMPLETED.name();
            } else if (successCount > 0) {
                newStatus = TaskStatus.PARTIAL_FAILED.name();
            } else {
                newStatus = TaskStatus.FAILED.name();
            }
            if (!newStatus.equals(task.getStatus())) {
                task.setStatus(newStatus);
                statusChanged = true;
            }
            if (task.getCompletedAt() == null) {
                task.setCompletedAt(LocalDateTime.now());
                statusChanged = true;
            }
        }
        taskMapper.updateById(task);
        if (pendingCount == 0 && statusChanged) {
            taskScheduleService.scheduleTask(task);
        }
    }

    public TaskProgressVO getTaskProgress(String taskNo) {
        Task task = getTaskByNo(taskNo);
        if (task == null) {
            throw new BusinessException(ResultCode.TASK_NOT_FOUND);
        }

        return TaskProgressVO.builder()
                .taskNo(task.getTaskNo())
                .status(task.getStatus())
                .totalCount(task.getTotalCount())
                .completedCount(task.getCompletedCount())
                .failedCount(task.getFailedCount())
                .totalAiCount(task.getTotalAiCount())
                .totalQuestionCount(task.getTotalQuestionCount())
                .percentage(task.getTotalCount() > 0 ?
                        (task.getCompletedCount().doubleValue() / task.getTotalCount()) * 100 : 0)
                .errorMsg(task.getErrorMsg())
                .createdAt(task.getCreatedAt())
                .completedAt(task.getCompletedAt())
                .build();
    }

    public List<TaskResultVO> getTaskResults(String taskNo) {
        Task task = getTaskByNo(taskNo);
        if (task == null) {
            throw new BusinessException(ResultCode.TASK_NOT_FOUND);
        }

        List<TaskAi> taskAis = taskAiMapper.selectByTaskNo(taskNo);
        Map<String, String> platformNameMap = new HashMap<>();
        for (TaskAi ai : taskAis) {
            platformNameMap.put(ai.getAiPlatform(), ai.getAiDisplayName());
        }

        String selfBrand = task.getBrandName();

        Map<String, String> aiSentimentMapFromReport = new HashMap<>();
        try {
            if (task.getReportJson() != null && !task.getReportJson().isEmpty()) {
                com.geo.dto.AnalysisReportResponse cachedReport = objectMapper.readValue(task.getReportJson(), com.geo.dto.AnalysisReportResponse.class);
                if (cachedReport.getAiSentimentMap() != null) {
                    aiSentimentMapFromReport = cachedReport.getAiSentimentMap();
                }
            }
        } catch (Exception e) {
            log.debug("解析缓存报告的AI情感数据失败: {}", e.getMessage());
        }

        List<TaskResult> results = taskResultMapper.selectByTaskNo(taskNo);
        List<TaskResultVO> voList = new ArrayList<>();
        for (TaskResult r : results) {
            boolean mentioned = false;
            String sentiment = "none";
            String sentimentSource = "word";
            try {
                if (selfBrand != null && !selfBrand.isEmpty()) {
                    String answerText = r.getAnswerText() != null ? r.getAnswerText() : "";
                    String combined = answerText.toLowerCase();
                    mentioned = findFirstBrandIndex(combined, selfBrand) >= 0;

                    String resultIdKey = String.valueOf(r.getId());
                    if (aiSentimentMapFromReport.containsKey(resultIdKey)) {
                        sentiment = aiSentimentMapFromReport.get(resultIdKey);
                        sentimentSource = "ai";
                    } else {
                        sentiment = SentimentUtils.analyzeBrandSentiment(answerText, selfBrand);
                    }
                }
            } catch (Exception ignored) {
                mentioned = false;
                sentiment = "none";
            }

            LocalDateTime queryTime = r.getCompletedAt();
            if (queryTime == null) {
                String status = r.getStatus();
                if (ResultStatus.SUCCESS.name().equals(status)
                        || ResultStatus.FAILED.name().equals(status)
                        || ResultStatus.TIMEOUT.name().equals(status)) {
                    queryTime = r.getCreatedAt();
                }
            }

            TaskResultVO vo = TaskResultVO.builder()
                    .id(r.getId())
                    .aiPlatform(r.getAiPlatform())
                    .aiDisplayName(platformNameMap.getOrDefault(r.getAiPlatform(), r.getAiPlatform()))
                    .questionText(r.getQuestionText())
                    .answerText(r.getAnswerText())
                    .thinkingContent(r.getThinkingContent())
                    .sourceInfo(r.getSourceInfo())
                    .screenshotUrls(parseScreenshotUrls(r.getScreenshotUrls()))
                    .status(r.getStatus())
                    .errorMsg(r.getErrorMsg())
                    .durationMs(r.getDurationMs())
                    .rpaRetryCount(r.getRpaRetryCount())
                    .createdAt(r.getCreatedAt())
                    .completedAt(r.getCompletedAt())
                    .queryTime(queryTime)
                    .showStatus(mentioned ? "SUCCESS" : "NOT_FOUND")
                    .brand(mentioned && selfBrand != null ? selfBrand : "-")
                    .sentiment(sentiment)
                    .sentimentSource(sentimentSource)
                    .build();
            voList.add(vo);
        }
        return voList;
    }

    private java.util.List<String> parseScreenshotUrls(String screenshotUrlsJson) {
        if (screenshotUrlsJson == null || screenshotUrlsJson.isEmpty()) {
            return new java.util.ArrayList<>();
        }
        try {
            java.util.List<String> rawUrls = objectMapper.readValue(screenshotUrlsJson, new TypeReference<List<String>>() {});
            java.util.List<String> normalizedUrls = new java.util.ArrayList<>(rawUrls.size());
            for (String url : rawUrls) {
                normalizedUrls.add(normalizeScreenshotUrl(url));
            }
            return normalizedUrls;
        } catch (JsonProcessingException e) {
            log.warn("解析截图URLs失败: {}", screenshotUrlsJson, e);
            return new java.util.ArrayList<>();
        }
    }

    private String normalizeScreenshotUrl(String url) {
        if (url == null || url.isEmpty()) {
            return url;
        }
        if (url.startsWith("/api/file/")) {
            return url;
        }
        int idx = url.indexOf("/geo-bucket/");
        if (idx >= 0) {
            return "/api/file" + url.substring(idx);
        }
        if (url.contains("geo-bucket")) {
            int bucketIdx = url.indexOf("geo-bucket");
            return "/api/file/" + url.substring(bucketIdx);
        }
        return url;
    }

    @Transactional
    public void updateTaskResult(Long taskResultId, String answerText, String screenshotUrl,
                                 String status, String errorMsg, Long durationMs) {
        TaskResult result = taskResultMapper.selectById(taskResultId);
        if (result == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "任务结果不存在");
        }

        result.setAnswerText(answerText);
        if (screenshotUrl != null && !screenshotUrl.isEmpty()) {
            List<String> urls = new ArrayList<>();
            urls.add(screenshotUrl);
            try {
                result.setScreenshotUrls(objectMapper.writeValueAsString(urls));
            } catch (JsonProcessingException e) {
                log.error("序列化截图URL失败", e);
            }
        }
        result.setStatus(status);
        result.setErrorMsg(errorMsg);
        result.setDurationMs(durationMs);
        result.setCompletedAt(LocalDateTime.now());
        taskResultMapper.updateById(result);

        updateTaskProgress(result.getTaskId());
    }

    @Transactional
    public void updateTaskResultWithMultipleScreenshots(Long taskResultId, String answerText,
                                                        java.util.List<String> screenshotUrls,
                                                        String status, String errorMsg, Long durationMs) {
        TaskResult result = taskResultMapper.selectById(taskResultId);
        if (result == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "任务结果不存在");
        }

        result.setAnswerText(answerText);
        if (screenshotUrls != null && !screenshotUrls.isEmpty()) {
            try {
                result.setScreenshotUrls(objectMapper.writeValueAsString(screenshotUrls));
            } catch (JsonProcessingException e) {
                log.error("序列化截图URLs失败", e);
            }
        }
        result.setStatus(status);
        result.setErrorMsg(errorMsg);
        result.setDurationMs(durationMs);
        result.setCompletedAt(LocalDateTime.now());
        taskResultMapper.updateById(result);

        updateTaskProgress(result.getTaskId());
    }

    @Transactional
    public void updateTaskProgress(Long taskId) {
        Task task = taskMapper.selectById(taskId);
        if (task == null) {
            return;
        }

        List<TaskResult> results = taskResultMapper.selectByTaskId(taskId);
        long successCount = 0;
        long failedCount = 0;
        long pendingCount = 0;
        for (TaskResult r : results) {
            String status = r.getStatus();
            if (ResultStatus.SUCCESS.name().equals(status)) {
                successCount++;
            } else if (ResultStatus.FAILED.name().equals(status) || ResultStatus.TIMEOUT.name().equals(status)) {
                failedCount++;
            } else {
                pendingCount++;
            }
        }

        task.setTotalCount(results.size());
        task.setCompletedCount((int) successCount);
        task.setFailedCount((int) failedCount);

        if (pendingCount == 0) {
            if (failedCount == 0) {
                task.setStatus(TaskStatus.COMPLETED.name());
            } else if (successCount > 0) {
                task.setStatus(TaskStatus.PARTIAL_FAILED.name());
            } else {
                task.setStatus(TaskStatus.FAILED.name());
            }
            task.setCompletedAt(LocalDateTime.now());
            task.setNextRunTime(calculateNextRunTime(task.getExecutionFrequency()));
        }

        taskMapper.updateById(task);

        if (pendingCount == 0) {
            taskScheduleService.scheduleTask(task);
        }
    }

    @Transactional
    public Task retryFailedTasks(String taskNo) {
        return retryTasks(taskNo, null);
    }

    @Transactional
    public Task retryAllTasks(String taskNo) {
        return retryTasks(taskNo, "all");
    }

    @Transactional
    public Task stopTask(String taskNo) {
        Task task = getTaskByNo(taskNo);
        if (task == null) {
            throw new BusinessException(ResultCode.TASK_NOT_FOUND);
        }
        if (TaskStatus.PROCESSING.name().equals(task.getStatus()) || TaskStatus.PENDING.name().equals(task.getStatus())
                || TaskStatus.PAUSED.name().equals(task.getStatus())) {
            task.setStatus(TaskStatus.CANCELLED.name());
            task.setErrorMsg("任务已手动终止");
            taskMapper.updateById(task);
        }
        List<TaskResult> results = taskResultMapper.selectByTaskNo(taskNo);
        for (TaskResult result : results) {
            if (ResultStatus.PENDING.name().equals(result.getStatus()) || ResultStatus.RUNNING.name().equals(result.getStatus())) {
                result.setStatus(ResultStatus.FAILED.name());
                result.setErrorMsg("任务已终止");
                taskResultMapper.updateById(result);
            }
        }
        return task;
    }

    @Transactional
    public Task pauseTask(String taskNo) {
        Task task = getTaskByNo(taskNo);
        if (task == null) {
            throw new BusinessException(ResultCode.TASK_NOT_FOUND);
        }
        if (!TaskStatus.PROCESSING.name().equals(task.getStatus())
                && !TaskStatus.PENDING.name().equals(task.getStatus())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "当前状态不允许暂停");
        }
        task.setStatus(TaskStatus.PAUSED.name());
        taskMapper.updateById(task);
        log.info("暂停任务: taskNo={}", taskNo);
        // 已在执行的单元会跑完；其余待执行单元因 dispatcher 排除 PAUSED 而不再被派发
        return task;
    }

    @Transactional
    public Task resumeTask(String taskNo) {
        Task task = getTaskByNo(taskNo);
        if (task == null) {
            throw new BusinessException(ResultCode.TASK_NOT_FOUND);
        }
        if (!TaskStatus.PAUSED.name().equals(task.getStatus())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "当前状态不允许恢复");
        }
        task.setStatus(TaskStatus.PROCESSING.name());
        taskMapper.updateById(task);
        log.info("恢复任务: taskNo={}", taskNo);
        return task;
    }

    @Transactional
    public Task retryTasks(String taskNo, String filter) {
        Task task = getTaskByNo(taskNo);
        if (task == null) {
            throw new BusinessException(ResultCode.TASK_NOT_FOUND);
        }

        List<TaskResult> results = taskResultMapper.selectByTaskNo(taskNo);
        List<TaskResult> retryResults = new ArrayList<>();

        for (TaskResult result : results) {
            String status = result.getStatus();
            boolean shouldRetry = false;
            if ("all".equals(filter)) {
                shouldRetry = true;
            } else if ("success".equals(filter)) {
                shouldRetry = ResultStatus.SUCCESS.name().equals(status);
            } else if (ResultStatus.FAILED.name().equals(status) || ResultStatus.TIMEOUT.name().equals(status)) {
                shouldRetry = true;
            }

            if (shouldRetry) {
                result.setStatus(ResultStatus.PENDING.name());
                result.setErrorMsg(null);
                result.setAnswerText(null);
                result.setScreenshotUrls(null);
                result.setDurationMs(null);
                result.setCompletedAt(null);
                result.setCreatedAt(LocalDateTime.now());
                result.setRpaRetryCount(result.getRpaRetryCount() != null ? result.getRpaRetryCount() + 1 : 1);
                taskResultMapper.updateById(result);
                retryResults.add(result);
            }
        }

        if (retryResults.isEmpty()) {
            if ("all".equals(filter)) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "没有任务需要重试");
            } else if ("success".equals(filter)) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "没有成功的任务需要重试");
            } else {
                throw new BusinessException(ResultCode.BAD_REQUEST, "没有失败的任务需要重试");
            }
        }

        task.setStatus(TaskStatus.PROCESSING.name());
        task.setCompletedAt(null);
        task.setNextRunTime(calculateNextRunTime(task.getExecutionFrequency()));
        taskMapper.updateById(task);
        taskScheduleService.scheduleTask(task);

        List<String> competitorsList = new ArrayList<>();
        if (task.getCompetitors() != null && !task.getCompetitors().isEmpty()) {
            try {
                competitorsList = objectMapper.readValue(task.getCompetitors(), new TypeReference<List<String>>() {});
            } catch (JsonProcessingException e) {
                log.warn("解析竞争对手列表失败", e);
            }
        }
        dispatchToRpa(taskNo, retryResults, task.getBrandName(), task.getProductName(), competitorsList, "single", true);

        log.info("重试任务: taskNo={}, retryCount={}, filter={}", taskNo, retryResults.size(), filter);
        return task;
    }

    @Transactional
    public Task retrySpecificTaskResult(Long taskResultId) {
        TaskResult result = taskResultMapper.selectById(taskResultId);
        if (result == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "任务结果不存在");
        }

        result.setStatus(ResultStatus.PENDING.name());
        result.setErrorMsg(null);
        result.setAnswerText(null);
        result.setScreenshotUrls(null);
        result.setDurationMs(null);
        result.setCompletedAt(null);
        result.setCreatedAt(LocalDateTime.now());
        result.setRpaRetryCount(result.getRpaRetryCount() != null ? result.getRpaRetryCount() + 1 : 1);
        taskResultMapper.updateById(result);

        Task task = taskMapper.selectById(result.getTaskId());
        if (task != null && !TaskStatus.PROCESSING.name().equals(task.getStatus())) {
            task.setStatus(TaskStatus.PROCESSING.name());
            task.setCompletedAt(null);
            task.setNextRunTime(calculateNextRunTime(task.getExecutionFrequency()));
            taskMapper.updateById(task);
            taskScheduleService.scheduleTask(task);
        }

        List<TaskResult> retryResults = new ArrayList<>();
        retryResults.add(result);

        List<String> competitorsList = new ArrayList<>();
        if (task != null && task.getCompetitors() != null && !task.getCompetitors().isEmpty()) {
            try {
                competitorsList = objectMapper.readValue(task.getCompetitors(), new TypeReference<List<String>>() {});
            } catch (JsonProcessingException e) {
                log.warn("解析竞争对手列表失败", e);
            }
        }
        dispatchToRpa(result.getTaskNo(), retryResults, task != null ? task.getBrandName() : "", task != null ? task.getProductName() : null, competitorsList, "single", true);

        log.info("重试单个任务: taskResultId={}, taskNo={}", taskResultId, result.getTaskNo());
        return task;
    }

    @Transactional
    public Task retrySuccessTasks(String taskNo) {
        return retryTasks(taskNo, "success");
    }

    @Transactional
    public Task deleteTaskResult(Long taskResultId) {
        TaskResult result = taskResultMapper.selectById(taskResultId);
        if (result == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "任务结果不存在");
        }

        if (ResultStatus.RUNNING.name().equals(result.getStatus())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "该问题正在执行中，无法删除");
        }

        Long taskId = result.getTaskId();
        String taskNo = result.getTaskNo();

        taskResultMapper.deleteById(taskResultId);

        Task task = taskMapper.selectById(taskId);
        if (task != null) {
            if (task.getTotalCount() != null && task.getTotalCount() > 0) {
                task.setTotalCount(task.getTotalCount() - 1);
            }
            if (task.getCompletedCount() != null && task.getCompletedCount() > 0 &&
                (ResultStatus.SUCCESS.name().equals(result.getStatus()))) {
                task.setCompletedCount(task.getCompletedCount() - 1);
            }
            if (task.getFailedCount() != null && task.getFailedCount() > 0 &&
                (ResultStatus.FAILED.name().equals(result.getStatus()) || ResultStatus.TIMEOUT.name().equals(result.getStatus()))) {
                task.setFailedCount(task.getFailedCount() - 1);
            }
            if (task.getReportJson() != null && !task.getReportJson().isEmpty()) {
                task.setReportJson(null);
            }

            taskMapper.updateById(task);
            updateTaskProgress(taskId);
        }

        log.info("删除单个任务结果: taskResultId={}, taskNo={}", taskResultId, taskNo);
        return task;
    }

    @Transactional
    public Task deleteTaskResults(List<Long> taskResultIds) {
        if (taskResultIds == null || taskResultIds.isEmpty()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "请选择要删除的问题");
        }

        String taskNo = null;
        Long taskId = null;

        for (Long id : taskResultIds) {
            TaskResult result = taskResultMapper.selectById(id);
            if (result == null) {
                continue;
            }
            if (ResultStatus.RUNNING.name().equals(result.getStatus())) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "选中的问题中有正在执行的，无法删除");
            }
            if (taskNo == null) {
                taskNo = result.getTaskNo();
                taskId = result.getTaskId();
            }
        }

        for (Long id : taskResultIds) {
            taskResultMapper.deleteById(id);
        }

        Task task = null;
        if (taskId != null) {
            task = taskMapper.selectById(taskId);
            if (task != null) {
                if (task.getReportJson() != null && !task.getReportJson().isEmpty()) {
                    task.setReportJson(null);
                    taskMapper.updateById(task);
                }
                updateTaskProgress(taskId);
            }
        }

        log.info("批量删除任务结果: count={}, taskNo={}", taskResultIds.size(), taskNo);
        return task;
    }

    @Transactional
    public Task retryTasksByStatus(String taskNo, String status) {
        Task task = getTaskByNo(taskNo);
        if (task == null) {
            throw new BusinessException(ResultCode.TASK_NOT_FOUND);
        }

        List<TaskResult> results = taskResultMapper.selectByTaskNo(taskNo);
        List<TaskResult> retryResults = new ArrayList<>();

        for (TaskResult result : results) {
            String resultStatus = result.getStatus();
            if (status.equalsIgnoreCase(resultStatus)) {
                result.setStatus(ResultStatus.PENDING.name());
                result.setErrorMsg(null);
                result.setAnswerText(null);
                result.setScreenshotUrls(null);
                result.setDurationMs(null);
                result.setCompletedAt(null);
                result.setCreatedAt(LocalDateTime.now());
                result.setRpaRetryCount(result.getRpaRetryCount() != null ? result.getRpaRetryCount() + 1 : 1);
                taskResultMapper.updateById(result);
                retryResults.add(result);
            }
        }

        if (retryResults.isEmpty()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "没有" + status + "状态的任务需要重试");
        }

        task.setStatus(TaskStatus.PROCESSING.name());
        task.setCompletedAt(null);
        taskMapper.updateById(task);

        List<String> competitorsList = new ArrayList<>();
        if (task.getCompetitors() != null && !task.getCompetitors().isEmpty()) {
            try {
                competitorsList = objectMapper.readValue(task.getCompetitors(), new TypeReference<List<String>>() {});
            } catch (JsonProcessingException e) {
                log.warn("解析竞争对手列表失败", e);
            }
        }
        dispatchToRpa(taskNo, retryResults, task.getBrandName(), task.getProductName(), competitorsList, "single", true);

        log.info("按状态重试任务: taskNo={}, retryCount={}, status={}", taskNo, retryResults.size(), status);
        return task;
    }

    public TaskRankingVO getTaskRankings(String taskNo) {
        Task task = getTaskByNo(taskNo);
        if (task == null) {
            throw new BusinessException(ResultCode.TASK_NOT_FOUND);
        }

        boolean isFinal = TaskStatus.COMPLETED.name().equals(task.getStatus())
                || TaskStatus.FAILED.name().equals(task.getStatus())
                || TaskStatus.PARTIAL_FAILED.name().equals(task.getStatus())
                || TaskStatus.CANCELLED.name().equals(task.getStatus());
        if (isFinal) {
            TaskRankingVO cached = rankingCache.get(taskNo);
            if (cached != null) {
                log.debug("使用缓存排名: taskNo={}", taskNo);
                return cached;
            }
        }

        String selfBrand = task.getBrandName();
        List<String> competitorBrands = parseCompetitors(task.getCompetitors());

        List<String> allBrands = new ArrayList<>();
        if (selfBrand != null && !selfBrand.trim().isEmpty()) {
            allBrands.add(selfBrand.trim());
        }
        allBrands.addAll(competitorBrands);

        List<TaskAi> taskAis = taskAiMapper.selectByTaskNo(taskNo);
        Map<String, String> platformNameMap = new HashMap<>();
        for (TaskAi ai : taskAis) {
            platformNameMap.put(ai.getAiPlatform(), ai.getAiDisplayName());
        }

        List<TaskQuestion> questions = taskQuestionMapper.selectByTaskNo(taskNo);
        Map<Long, TaskQuestion> questionMap = new HashMap<>();
        for (TaskQuestion q : questions) {
            questionMap.put(q.getId(), q);
        }

        List<TaskResult> results = taskResultMapper.selectByTaskNo(taskNo);
        results = results.stream()
                .sorted(Comparator.comparing((TaskResult r) -> {
                    TaskQuestion q = questionMap.get(r.getTaskQuestionId());
                    return q != null && q.getSortOrder() != null ? q.getSortOrder() : Integer.MAX_VALUE;
                }).thenComparing(r -> r.getCreatedAt() != null ? r.getCreatedAt() : LocalDateTime.MIN))
                .collect(Collectors.toList());

        List<TaskRankingVO.QuestionRanking> questionRankings = new ArrayList<>();
        Map<String, List<Integer>> brandAllRanks = new LinkedHashMap<>();
        for (String brand : allBrands) {
            brandAllRanks.put(brand, new ArrayList<>());
        }

        for (TaskResult r : results) {
            TaskRankingVO.QuestionRanking qr = new TaskRankingVO.QuestionRanking();
            qr.setResultId(r.getId());
            qr.setQuestionId(r.getTaskQuestionId());
            qr.setQuestionText(r.getQuestionText());
            qr.setAiPlatform(r.getAiPlatform());
            qr.setAiDisplayName(platformNameMap.getOrDefault(r.getAiPlatform(), r.getAiPlatform()));
            qr.setStatus(r.getStatus());
            qr.setCompletedAt(r.getCompletedAt());

            TaskQuestion tq = questionMap.get(r.getTaskQuestionId());
            if (tq != null) {
                qr.setSortOrder(tq.getSortOrder());
            }

            List<TaskRankingVO.BrandRankItem> brandRankings = analyzeAnswerBrandRanking(
                    r.getAnswerText(), r.getThinkingContent(), allBrands, selfBrand, brandAllRanks);
            qr.setBrandRankings(brandRankings);
            questionRankings.add(qr);
        }

        int validAnswers = (int) results.stream()
                .filter(r -> ResultStatus.SUCCESS.name().equals(r.getStatus()))
                .count();

        List<TaskRankingVO.BrandAggregatedRanking> aggregatedRankings = new ArrayList<>();
        for (String brand : allBrands) {
            boolean isSelf = brand.equals(selfBrand);
            List<Integer> ranks = brandAllRanks.getOrDefault(brand, Collections.emptyList());

            TaskRankingVO.BrandAggregatedRanking agg = new TaskRankingVO.BrandAggregatedRanking();
            agg.setBrandName(brand);
            agg.setIsSelfBrand(isSelf);
            agg.setAllRanks(new ArrayList<>(ranks));

            int mentionCount = ranks.size();
            int firstCount = 0, top3Count = 0, top5Count = 0;
            Map<Integer, Integer> distribution = new TreeMap<>();
            for (Integer rank : ranks) {
                if (rank != null) {
                    distribution.merge(rank, 1, Integer::sum);
                    if (rank == 1) firstCount++;
                    if (rank <= 3) top3Count++;
                    if (rank <= 5) top5Count++;
                }
            }

            agg.setMentionCount(mentionCount);
            agg.setFirstCount(firstCount);
            agg.setTop3Count(top3Count);
            agg.setTop5Count(top5Count);
            agg.setMentionRate(validAnswers > 0 ? Math.round((mentionCount * 10000.0) / validAnswers) / 100.0 : 0.0);
            agg.setFirstRate(validAnswers > 0 ? Math.round((firstCount * 10000.0) / validAnswers) / 100.0 : 0.0);
            agg.setTop3Rate(validAnswers > 0 ? Math.round((top3Count * 10000.0) / validAnswers) / 100.0 : 0.0);
            agg.setTop5Rate(validAnswers > 0 ? Math.round((top5Count * 10000.0) / validAnswers) / 100.0 : 0.0);
            agg.setRankDistribution(distribution);

            aggregatedRankings.add(agg);
        }

        TaskRankingVO vo = new TaskRankingVO();
        vo.setTaskNo(taskNo);
        vo.setTitle(task.getTitle());
        vo.setBrandName(selfBrand);
        vo.setCompetitorBrands(competitorBrands);
        vo.setQuestionRankings(questionRankings);
        vo.setAggregatedRankings(aggregatedRankings);
        vo.setTotalAnswers(results.size());
        vo.setValidAnswers(validAnswers);

        if (isFinal) {
            rankingCache.put(taskNo, vo);
            log.debug("排名已缓存: taskNo={}, questionCount={}", taskNo, questionRankings.size());
        }
        return vo;
    }

    private List<TaskRankingVO.BrandRankItem> analyzeAnswerBrandRanking(
            String answerText, String thinkingContent,
            List<String> allBrands, String selfBrand,
            Map<String, List<Integer>> brandAllRanks) {

        String combined = (answerText != null ? answerText : "");

        if (combined.trim().isEmpty()) {
            List<TaskRankingVO.BrandRankItem> empty = new ArrayList<>();
            for (String brand : allBrands) {
                empty.add(new TaskRankingVO.BrandRankItem(brand, brand.equals(selfBrand), null, -1, null));
            }
            return empty;
        }

        String lowerCombined = combined.toLowerCase();
        Map<String, Integer> firstIndexMap = new LinkedHashMap<>();
        Map<String, String> matchedTextMap = new LinkedHashMap<>();

        for (String brand : allBrands) {
            if (brand == null || brand.isEmpty()) continue;
            int idx = findFirstBrandIndex(lowerCombined, brand);
            if (idx >= 0) {
                firstIndexMap.put(brand, idx);
                int matchLen = Math.max(4, brand.length());
                int contextLen = 8;
                int snippetStart = Math.max(0, idx - contextLen);
                int snippetEnd = Math.min(combined.length(), idx + matchLen + contextLen);
                String snippet = combined.substring(snippetStart, snippetEnd);
                if (snippetStart > 0) snippet = "、" + snippet;
                if (snippetEnd < combined.length()) snippet = snippet + " ";
                matchedTextMap.put(brand, snippet);
            }
        }

        List<Map.Entry<String, Integer>> sorted = firstIndexMap.entrySet().stream()
                .sorted(Map.Entry.comparingByValue())
                .collect(Collectors.toList());

        Map<String, Integer> brandRank = new LinkedHashMap<>();
        int position = 1;
        for (Map.Entry<String, Integer> entry : sorted) {
            brandRank.put(entry.getKey(), position++);
            brandAllRanks.computeIfAbsent(entry.getKey(), k -> new ArrayList<>()).add(brandRank.get(entry.getKey()));
        }

        List<TaskRankingVO.BrandRankItem> result = new ArrayList<>();
        for (String brand : allBrands) {
            Integer rank = brandRank.get(brand);
            Integer firstIndex = firstIndexMap.get(brand);
            String matched = matchedTextMap.get(brand);
            boolean isSelf = brand.equals(selfBrand);

            if (rank == null) {
                result.add(new TaskRankingVO.BrandRankItem(brand, isSelf, null, -1, null));
            } else {
                result.add(new TaskRankingVO.BrandRankItem(brand, isSelf, rank, firstIndex, matched));
            }
        }

        return result;
    }

    private int findFirstBrandIndex(String text, String brand) {
        if (text == null || brand == null || brand.isEmpty()) return -1;

        String lowerText = text.toLowerCase();
        String lowerBrand = brand.toLowerCase();

        return lowerText.indexOf(lowerBrand);
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

    public LocalDateTime calculateNextRunTime(String executionFrequency) {
        if (executionFrequency == null) {
            return null;
        }
        switch (executionFrequency) {
            case "daily":
                return LocalDateTime.now().plusDays(1);
            case "weekly":
                return LocalDateTime.now().plusWeeks(1);
            default:
                return null;
        }
    }

    @Transactional
    public int rescheduleDueTasks() {
        com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<Task> wrapper =
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<>();
        wrapper.isNotNull("next_run_time");
        wrapper.le("next_run_time", LocalDateTime.now());
        wrapper.in("status",
                TaskStatus.COMPLETED.name(),
                TaskStatus.PARTIAL_FAILED.name(),
                TaskStatus.FAILED.name());
        List<Task> dueTasks = taskMapper.selectList(wrapper);
        if (dueTasks == null || dueTasks.isEmpty()) {
            return 0;
        }

        int triggered = 0;
        for (Task task : dueTasks) {
            try {
                retryAllTasks(task.getTaskNo());
                task.setNextRunTime(calculateNextRunTime(task.getExecutionFrequency()));
                taskMapper.updateById(task);
                taskScheduleService.scheduleTask(task);
                triggered++;
                log.info("周期调度触发任务重跑: taskNo={}, frequency={}",
                        task.getTaskNo(), task.getExecutionFrequency());
            } catch (Exception e) {
                log.error("周期任务触发失败: taskNo={}", task.getTaskNo(), e);
            }
        }
        return triggered;
    }

    private boolean isTaskDue(Task task) {
        if (task == null || task.getNextRunTime() == null) return false;
        String freq = task.getExecutionFrequency();
        if (freq == null || "single".equals(freq)) return false;
        return !task.getNextRunTime().isAfter(LocalDateTime.now());
    }
}