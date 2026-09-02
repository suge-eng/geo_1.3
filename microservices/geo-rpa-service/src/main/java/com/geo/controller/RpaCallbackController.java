package com.geo.controller;

import com.geo.common.Result;
import com.geo.service.MinioService;
import com.geo.service.RpaWorkerDispatcherService;
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

/**
 * worker 结果回报侧入口：承接 worker 执行完成后回传的结果，以及执行过程中的截图上传。
 *
 * 与 {@link InternalWorkerController} 的分工：
 *   - InternalWorkerController 提供 worker 的「认领/心跳/中止」三件套；
 *   - 本控制器负责 worker 的「结果回报」——即回调结果、上传截图，
 *     二者合起来构成 worker 脚本与本服务之间的完整 HTTP 协议。
 *
 * 路径以 /api/rpa 开头，走网关；截图上传与结果回调都可能由 worker 直接发起，
 * 因此这里不要求登录态，而是靠 taskNo / taskResultId 等业务字段来定位与校验。
 */
@RestController
@RequestMapping("/api/rpa")
public class RpaCallbackController {

    private static final Logger log = LoggerFactory.getLogger(RpaCallbackController.class);

    private final RestTemplate restTemplate;
    private final MinioService minioService;
    private final TaskResultMapper taskResultMapper;
    private final RpaWorkerDispatcherService dispatcher;
    private final ObjectMapper objectMapper;

    public RpaCallbackController(RestTemplate restTemplate, MinioService minioService,
                                  TaskResultMapper taskResultMapper,
                                  RpaWorkerDispatcherService dispatcher,
                                  ObjectMapper objectMapper) {
        this.restTemplate = restTemplate;
        this.minioService = minioService;
        this.taskResultMapper = taskResultMapper;
        this.dispatcher = dispatcher;
        this.objectMapper = objectMapper;
    }

    /**
     * 任务池看板：返回全局任务池的实时分布数据，供前端监控。
     * 网关 /api/rpa/pool 会转发到这里。
     */
    @GetMapping("/pool")
    public Result<Map<String, Object>> taskPoolOverview() {
        Map<String, Object> data = new HashMap<>();
        data.put("statusCount", taskResultMapper.countByStatus());
        data.put("platformCount", taskResultMapper.countByPlatform());
        data.put("workerCount", taskResultMapper.countByAssignee());
        data.put("runningUnits", taskResultMapper.selectRunningUnits());
        return Result.success("ok", data);
    }

    /**
     * 接收截图上传：worker 在执行过程中把截图（如验证码页、结果页）POST 上来，
     * 校验为图片后交给 MinioService 落盘，返回外网可访问的 URL 供后续回调引用。
     */
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

    /**
     * worker 结果回调：处理一个单元执行完成后的回报，把结果写入对应记录。
     *
     * 核心难点是「把回调定位到数据库里的哪条记录」——worker 回传的字段可能不全或不稳，
     * 因此采用逐级降级的匹配策略（优先级从高到低）：
     *   1. taskResultId 精确匹配（最可靠）；
     *   2. aiPlatform + questionText + PENDING 状态匹配（平台用 code）；
     *   3. aiPlatform + questionText 匹配（不限状态）；
     *   4. 平台「显示名」+ questionText + PENDING 匹配（兼容 worker 传中文显示名）；
     *   5. 平台「显示名」+ questionText 匹配（兜底）。
     * 全部失败则拒绝回调，避免把结果写到错误的记录上。
     */
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

        // 匹配优先级第 1 级：用 taskResultId 直接精确定位，最可靠。
        if (taskResultId != null) {
            for (TaskResult r : results) {
                if (taskResultId.equals(r.getId())) {
                    targetResult = r;
                    log.info("用 taskResultId 精确匹配到记录: id={}", r.getId());
                    break;
                }
            }
        }

        // 匹配优先级第 2/3 级：aiPlatform(code) + questionText，先限定 PENDING，再放宽到不限状态。
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

        // 匹配优先级第 4/5 级：worker 可能回传中文显示名，这里反向映射后按「显示名 + questionText」匹配，
        // 先限定 PENDING，再放宽到不限状态，作为前几级的兜底。
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

        // SUCCESS/FAILED/TIMEOUT 均为终态，只有终态才需要做收尾处理（清理租约、推进整体进度）。
        boolean terminal = ResultStatus.SUCCESS.name().equalsIgnoreCase(status)
                || ResultStatus.FAILED.name().equalsIgnoreCase(status)
                || ResultStatus.TIMEOUT.name().equalsIgnoreCase(status);

        if (terminal) {
            String taskStatus = null;
            try {
                taskStatus = taskResultMapper.selectTaskStatusByNo(taskNo);
            } catch (Exception e) {
                log.warn("查询任务状态失败: taskNo={}", taskNo, e);
            }
            // 用户已取消的任务：即便是成功的回调也算「逾期」，一律降级为失败，避免污染已终止任务的统计。
            if ("CANCELLED".equals(taskStatus) && ResultStatus.SUCCESS.name().equalsIgnoreCase(status)) {
                log.info("任务已取消，忽略逾期成功回调: taskNo={}, unitId={}", taskNo, targetResult.getId());
                status = ResultStatus.FAILED.name();
                errorMsg = "任务已终止，忽略逾期回调结果";
            }
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

        // 终态时释放该单元的租约：单元已达终态，不再需要心跳/回收，交给 Dispatcher.finish 清理租约字段。
        if (terminal) {
            dispatcher.finish(targetResult.getId());
        }

        log.info("任务结果更新成功: id={}, questionText={}, status={}, screenshotUrls={}",
                targetResult.getId(), targetResult.getQuestionText(), status, screenshotUrls);

        return Result.success("回调处理成功");
    }

    /**
     * 从截图 URL 的文件名中解析平台名。约定文件名形如 xx_{platform}_xxx，
     * 当 worker 未直接回传 aiPlatform 时作为兜底线索。
     */
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

    /**
     * 平台 code -> 中文显示名（用于匹配 worker 回传中文名的场景）。
     */
    private String getPlatformDisplayName(String platformCode) {
        for (AiPlatform platform : AiPlatform.values()) {
            if (platform.getCode().equals(platformCode)) {
                return platform.getDisplayName();
            }
        }
        return platformCode;
    }

    /**
     * 平台显示名 -> code（与上一个方法互为反向映射，用于把 worker 传的中文名归一成统一 code）。
     */
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
