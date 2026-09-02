package com.geo.controller;

import com.geo.common.Result;
import com.geo.dto.AnalysisReportResponse;
import com.geo.service.AnalysisService;
import com.geo.service.AnalysisService.ReportGenStatus;
import com.geo.service.AnalysisService.ReportGenerationStatus;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.BufferedReader;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 【分析报告REST API控制器】
 * 设计思路：
 * 1. 报告生成采用"同步优先、异步兜底"策略：
 *    - 任务结果少（默认<100条）：同步生成，返回HTTP 200+报告数据
 *    - 任务结果多或明确async=true：HTTP 202(Accepted)+状态回传，前端轮询status接口或监听WebSocket
 * 2. 防重复提交：RUNNING状态下重复请求直接返回202告诉用户"正在生成中"
 * 3. 全部响应禁用HTTP缓存：报告是实时计算/重生成的，浏览器缓存会导致数据陈旧
 *    （Cache-Control: no-store + Pragma: no-cache + Expires: 0 三件套）
 */
@RestController
@RequestMapping("/api/analysis")
public class AnalysisController {

    private static final Logger log = LoggerFactory.getLogger(AnalysisController.class);

    private final AnalysisService analysisService;

    public AnalysisController(AnalysisService analysisService) {
        this.analysisService = analysisService;
    }

    /**
     * 【获取分析报告】
     * 设计思路：默认策略，如果有缓存报告直接返回，否则根据数据量自动决定同步/异步生成
     */
    @GetMapping("/{taskNo}/report")
    public ResponseEntity<Result<?>> getReport(
            @PathVariable String taskNo,
            @RequestParam(value = "async", required = false) Boolean asyncParam) {
        log.info("获取分析报告: taskNo={}, async={}", taskNo, asyncParam);
        return handleReportRequest(taskNo, false, asyncParam);
    }

    /**
     * 【强制重新生成报告】
     * 设计思路：
     * - forceRegenerate=true，忽略缓存重新计算
     * - 预留options请求体（用户可能在前端调整加权参数、选择模型等），供后续扩展
     */
    @PostMapping("/{taskNo}/regenerate")
    public ResponseEntity<Result<?>> regenerateReport(
            @PathVariable String taskNo,
            @RequestParam(value = "async", required = false) Boolean asyncParam,
            HttpServletRequest request) {
        Map<String, Object> options = null;
        try {
            BufferedReader reader = request.getReader();
            String rawBody = reader.lines().collect(Collectors.joining("\n"));
            if (rawBody != null && !rawBody.trim().isEmpty()) {
                options = new com.fasterxml.jackson.databind.ObjectMapper().readValue(rawBody, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
            }
        } catch (Exception e) {
            log.warn("解析请求体失败，忽略: {}", e.getMessage());
        }
        log.info("重新生成分析报告: taskNo={}, async={}, options={}", taskNo, asyncParam, options);
        return handleReportRequest(taskNo, true, asyncParam);
    }

    /**
     * 【查询报告生成状态】
     * 设计思路：异步模式下前端轮询的接口，返回RUNNING/COMPLETED/FAILED及进度百分比、当前阶段
     */
    @GetMapping("/{taskNo}/report/status")
    public ResponseEntity<Result<Map<String, Object>>> getReportStatus(@PathVariable String taskNo) {
        log.info("查询报告生成状态: taskNo={}", taskNo);
        ReportGenerationStatus genStatus = analysisService.getReportGenerationStatus(taskNo);
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store, no-cache, must-revalidate, max-age=0")
                .header(HttpHeaders.PRAGMA, "no-cache")
                .header(HttpHeaders.EXPIRES, "0")
                .body(Result.success(buildStatusPayload(taskNo, genStatus)));
    }

    /**
     * 【报告请求通用处理方法】
     * 设计思路：
     * 1. 先查是否已在生成中（RUNNING），是则直接返回202 Accepted，避免重复计算
     * 2. useAsync决策逻辑：
     *    - 用户显式传async=true → 异步
     *    - 有缓存 → 同步（直接读缓存很快）
     *    - 否则：shouldUseAsync根据结果条数是否>reportAsyncThreshold(默认100)判断
     * 3. 同步失败会抛BusinessException被GlobalExceptionHandler统一捕获
     */
    private ResponseEntity<Result<?>> handleReportRequest(String taskNo, boolean forceRegenerate, Boolean asyncParam) {
        ReportGenerationStatus currentStatus = analysisService.getReportGenerationStatus(taskNo);

        if (currentStatus.getStatus() == ReportGenStatus.RUNNING) {
            Map<String, Object> payload = buildStatusPayload(taskNo, currentStatus);
            return ResponseEntity.status(HttpStatus.ACCEPTED)
                    .header(HttpHeaders.CACHE_CONTROL, "no-store, no-cache, must-revalidate, max-age=0")
                    .body(Result.success("报告生成中，请通过status接口或WebSocket查询进度", payload));
        }

        boolean useAsync = Boolean.TRUE.equals(asyncParam);
        if (asyncParam == null && !forceRegenerate) {
            if (!hasCachedReport(taskNo)) {
                useAsync = analysisService.shouldUseAsync(taskNo);
            }
        }

        if (useAsync) {
            analysisService.generateReportAsync(taskNo, forceRegenerate);
            ReportGenerationStatus newStatus = analysisService.getReportGenerationStatus(taskNo);
            Map<String, Object> payload = buildStatusPayload(taskNo, newStatus);
            return ResponseEntity.status(HttpStatus.ACCEPTED)
                    .header(HttpHeaders.CACHE_CONTROL, "no-store, no-cache, must-revalidate, max-age=0")
                    .body(Result.success("已启动异步报告生成，请通过status接口或WebSocket查询进度", payload));
        }

        AnalysisReportResponse report = analysisService.generateReport(taskNo, forceRegenerate);
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store, no-cache, must-revalidate, max-age=0")
                .header(HttpHeaders.PRAGMA, "no-cache")
                .header(HttpHeaders.EXPIRES, "0")
                .body(Result.success(forceRegenerate ? "报告已重新生成" : "success", report));
    }

    private boolean hasCachedReport(String taskNo) {
        return analysisService.hasCachedReport(taskNo);
    }

    /**
     * 【构建状态查询返回体】
     * 设计思路：用LinkedHashMap保证字段顺序稳定，前端解析时字段顺序可预测
     * 注：只有COMPLETED状态才把完整报告塞到返回体，避免RUNNING状态传大JSON浪费带宽
     */
    private Map<String, Object> buildStatusPayload(String taskNo, ReportGenerationStatus s) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("taskNo", taskNo);
        payload.put("status", s.getStatus() != null ? s.getStatus().name() : ReportGenStatus.IDLE.name());
        payload.put("progressPercent", s.getProgressPercent());
        payload.put("stage", s.getStage());
        payload.put("errorMsg", s.getErrorMsg());
        payload.put("startedAt", s.getStartedAt());
        payload.put("finishedAt", s.getFinishedAt());
        if (s.getStatus() == ReportGenStatus.COMPLETED && s.getReport() != null) {
            payload.put("report", s.getReport());
        }
        return payload;
    }

    /**
     * 【历史报告列表】
     * 设计思路：每次生成报告都会在task_report表插一条快照，这里返回该任务的所有历史版本
     */
    @GetMapping("/{taskNo}/reports/history")
    public ResponseEntity<Result<List<Map<String, Object>>>> getReportHistory(@PathVariable String taskNo) {
        log.info("获取历史报告列表: taskNo={}", taskNo);
        List<Map<String, Object>> history = analysisService.listReportHistory(taskNo);
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store, no-cache, must-revalidate, max-age=0")
                .header(HttpHeaders.PRAGMA, "no-cache")
                .header(HttpHeaders.EXPIRES, "0")
                .body(Result.success(history));
    }

    /**
     * 【按ID获取指定历史报告】
     * 设计思路：与taskNo维度的最新报告不同，reportId定位到具体某次快照
     */
    @GetMapping("/reports/{reportId}")
    public ResponseEntity<Result<AnalysisReportResponse>> getReportById(@PathVariable Long reportId) {
        log.info("获取指定历史报告: reportId={}", reportId);
        AnalysisReportResponse report = analysisService.getReportById(reportId);
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store, no-cache, must-revalidate, max-age=0")
                .header(HttpHeaders.PRAGMA, "no-cache")
                .header(HttpHeaders.EXPIRES, "0")
                .body(Result.success(report));
    }

    /**
     * 【生成全局数据报告】
     * 设计思路：独立报告生成入口，用于生成跨任务/跨品牌的全局数据报告（保留扩展点）
     */
    @PostMapping("/{taskNo}/reports/global/generate")
    public ResponseEntity<Result<AnalysisReportResponse>> generateGlobalReport(@PathVariable String taskNo) {
        log.info("生成全局数据报告: taskNo={}", taskNo);
        AnalysisReportResponse report = analysisService.generateGlobalReport(taskNo);
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store, no-cache, must-revalidate, max-age=0")
                .header(HttpHeaders.PRAGMA, "no-cache")
                .header(HttpHeaders.EXPIRES, "0")
                .body(Result.success(report));
    }
}