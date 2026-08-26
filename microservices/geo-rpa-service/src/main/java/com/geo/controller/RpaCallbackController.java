package com.geo.controller;

import com.geo.common.Result;
import com.geo.service.MinioService;
import com.geo.entity.TaskResult;
import com.geo.enums.AiPlatform;
import com.geo.enums.ResultStatus;
import com.geo.mapper.TaskResultMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/rpa")
public class RpaCallbackController {

    private static final Logger log = LoggerFactory.getLogger(RpaCallbackController.class);

    private final RestTemplate restTemplate;
    private final MinioService minioService;
    private final TaskResultMapper taskResultMapper;
    private final ObjectMapper objectMapper;

    public RpaCallbackController(RestTemplate restTemplate, MinioService minioService,
                                  TaskResultMapper taskResultMapper, ObjectMapper objectMapper) {
        this.restTemplate = restTemplate;
        this.minioService = minioService;
        this.taskResultMapper = taskResultMapper;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/upload")
    public Result<String> uploadScreenshot(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "taskId", required = false) String taskId) {

        log.info("收到RPA截图上传: taskId={}, filename={}, size={}KB",
                taskId, file.getOriginalFilename(), file.getSize() / 1024);

        if (file == null || file.isEmpty()) {
            return Result.fail(400, "上传文件不能为空");
        }

        String contentType = file.getContentType();
        if (contentType == null || !contentType.startsWith("image/")) {
            return Result.fail(400, "只能上传图片文件");
        }

        try {
            String fileUrl = minioService.uploadFile(file);
            log.info("截图上传成功: taskId={}, url={}", taskId, fileUrl);
            return Result.success("上传成功", fileUrl);
        } catch (Exception e) {
            log.error("截图上传失败: taskId={}", taskId, e);
            return Result.fail(500, "上传失败: " + e.getMessage());
        }
    }

    @PostMapping("/callback")
    @Transactional
    public Result<String> handleRpaCallback(@RequestBody Map<String, Object> request) {

        String taskNo = (String) request.get("taskNo");
        Long taskResultId = request.get("taskResultId") != null ? ((Number) request.get("taskResultId")).longValue() : null;
        String aiPlatform = (String) request.get("aiPlatform");
        String questionText = (String) request.get("questionText");
        @SuppressWarnings("unchecked")
        List<String> screenshotUrls = (List<String>) request.get("screenshotUrls");
        String status = (String) request.get("status");
        String errorMsg = (String) request.get("errorMsg");
        Long durationMs = request.get("durationMs") != null ? ((Number) request.get("durationMs")).longValue() : null;

        log.info("收到RPA回调: taskNo={}, taskResultId={}, aiPlatform={}, questionText={}, status={}", taskNo, taskResultId, aiPlatform, questionText, status);

        if (taskNo == null || taskNo.isEmpty()) {
            log.warn("回调中taskNo为空，跳过更新");
            return Result.fail(400, "taskNo不能为空");
        }

        QueryWrapper<TaskResult> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("task_no", taskNo);
        List<TaskResult> results = taskResultMapper.selectList(queryWrapper);

        if (results == null || results.isEmpty()) {
            log.warn("未找到匹配的任务结果: taskNo={}", taskNo);
            return Result.fail(404, "未找到匹配的任务记录");
        }

        log.info("查询到 {} 条 taskNo={} 的记录", results.size(), taskNo);

        TaskResult targetResult = null;

        if (aiPlatform == null || aiPlatform.isEmpty()) {
            String extractedName = extractPlatformFromScreenshotUrl(screenshotUrls);
            if (extractedName != null) {
                log.info("从截图URL中提取到平台名称: {}", extractedName);
                String code = getPlatformCodeFromDisplayName(extractedName);
                if (!extractedName.equals(code)) {
                    log.info("平台显示名称 {} 转换为代码: {}", extractedName, code);
                }
                aiPlatform = code;
            }
        }

        if (taskResultId != null) {
            for (TaskResult r : results) {
                if (taskResultId.equals(r.getId())) {
                    targetResult = r;
                    log.info("用 taskResultId 精确匹配到记录: id={}", r.getId());
                    break;
                }
            }
        }

        if (targetResult == null && aiPlatform != null && !aiPlatform.isEmpty() && questionText != null && !questionText.isEmpty()) {
            for (TaskResult r : results) {
                if (aiPlatform.equals(r.getAiPlatform()) && questionText.equals(r.getQuestionText())
                        && ResultStatus.PENDING.name().equals(r.getStatus())) {
                    targetResult = r;
                    log.info("用 aiPlatform + questionText + PENDING 精确匹配到记录: id={}, platform={}, question={}", r.getId(), aiPlatform, questionText);
                    break;
                }
            }
            if (targetResult == null) {
                for (TaskResult r : results) {
                    if (aiPlatform.equals(r.getAiPlatform()) && questionText.equals(r.getQuestionText())) {
                        targetResult = r;
                        log.info("用 aiPlatform + questionText 精确匹配到记录(非PENDING): id={}, platform={}, question={}, status={}", r.getId(), aiPlatform, questionText, r.getStatus());
                        break;
                    }
                }
            }
        }

        if (targetResult == null && aiPlatform != null && !aiPlatform.isEmpty() && questionText != null && !questionText.isEmpty()) {
            for (TaskResult r : results) {
                if (questionText.equals(r.getQuestionText())) {
                    String displayName = getPlatformDisplayName(r.getAiPlatform());
                    if (aiPlatform.equals(displayName) && ResultStatus.PENDING.name().equals(r.getStatus())) {
                        targetResult = r;
                        log.info("用平台显示名称 + questionText + PENDING 匹配到记录: id={}, platform={}, question={}", r.getId(), aiPlatform, questionText);
                        break;
                    }
                }
            }
            if (targetResult == null) {
                for (TaskResult r : results) {
                    if (questionText.equals(r.getQuestionText())) {
                        String displayName = getPlatformDisplayName(r.getAiPlatform());
                        if (aiPlatform.equals(displayName)) {
                            targetResult = r;
                            log.info("用平台显示名称 + questionText 匹配到记录(非PENDING): id={}, platform={}, question={}, status={}", r.getId(), aiPlatform, questionText, r.getStatus());
                            break;
                        }
                    }
                }
            }
        }

        if (targetResult == null) {
            log.warn("无法精确匹配到任务记录: taskNo={}, aiPlatform={}, questionText={}, taskResultId={}", taskNo, aiPlatform, questionText, taskResultId);
            log.warn("可用记录列表:");
            for (TaskResult r : results) {
                log.warn("  - id={}, aiPlatform={}, questionText={}, status={}", r.getId(), r.getAiPlatform(), r.getQuestionText(), r.getStatus());
            }
            return Result.fail(400, "无法精确匹配到任务记录，请确保RPA回调时传回 aiPlatform 参数");
        }

        targetResult.setStatus(status);
        targetResult.setErrorMsg(errorMsg);
        targetResult.setDurationMs(durationMs);
        targetResult.setCompletedAt(LocalDateTime.now());

        String answerText = (String) request.get("answerText");
        String thinkingContent = (String) request.get("thinkingContent");
        String sourceInfo = (String) request.get("sourceInfo");

        if (answerText != null) {
            targetResult.setAnswerText(answerText);
        }
        if (thinkingContent != null) {
            targetResult.setThinkingContent(thinkingContent);
        }
        if (sourceInfo != null) {
            targetResult.setSourceInfo(sourceInfo);
        }

        if (screenshotUrls != null && !screenshotUrls.isEmpty()) {
            try {
                targetResult.setScreenshotUrls(objectMapper.writeValueAsString(screenshotUrls));
            } catch (Exception e) {
                log.error("序列化截图URL失败", e);
            }
        }

        taskResultMapper.updateById(targetResult);

        try {
            restTemplate.postForEntity(
                    "http://localhost:8081/internal/task/" + targetResult.getTaskId() + "/updateProgress",
                    null,
                    Void.class
            );
        } catch (Exception e) {
            log.error("调用 task-service 更新任务进度失败: taskId={}", targetResult.getTaskId(), e);
        }

        log.info("任务结果更新成功: id={}, questionText={}, status={}, screenshotUrls={}",
                targetResult.getId(), targetResult.getQuestionText(), status, screenshotUrls);

        return Result.success("回调处理成功");
    }

    private String extractPlatformFromScreenshotUrl(List<String> screenshotUrls) {
        if (screenshotUrls == null || screenshotUrls.isEmpty()) {
            return null;
        }
        String url = screenshotUrls.get(0);
        String filename = url.substring(url.lastIndexOf('/') + 1);
        String[] parts = filename.split("_");
        if (parts.length >= 2) {
            return parts[1];
        }
        return null;
    }

    private String getPlatformDisplayName(String platformCode) {
        for (AiPlatform platform : AiPlatform.values()) {
            if (platform.getCode().equals(platformCode)) {
                return platform.getDisplayName();
            }
        }
        return platformCode;
    }

    private String getPlatformCodeFromDisplayName(String displayName) {
        if (displayName == null) {
            return null;
        }
        for (AiPlatform platform : AiPlatform.values()) {
            if (platform.getDisplayName().equals(displayName)) {
                return platform.getCode();
            }
        }
        return displayName;
    }
}
