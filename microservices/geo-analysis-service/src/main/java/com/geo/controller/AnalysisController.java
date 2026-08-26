package com.geo.controller;

import com.geo.common.Result;
import com.geo.dto.AnalysisReportResponse;
import com.geo.service.AnalysisService;
import com.geo.service.AnalysisService.ReportGenStatus;
import com.geo.service.AnalysisService.ReportGenerationStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/analysis")
public class AnalysisController {

    private static final Logger log = LoggerFactory.getLogger(AnalysisController.class);

    private final AnalysisService analysisService;

    public AnalysisController(AnalysisService analysisService) {
        this.analysisService = analysisService;
    }

    @GetMapping("/{taskNo}/report")
    public ResponseEntity<Result<?>> getReport(
            @PathVariable String taskNo,
            @RequestParam(value = "async", required = false) Boolean asyncParam) {
        log.info("获取分析报告: taskNo={}, async={}", taskNo, asyncParam);
        return handleReportRequest(taskNo, false, asyncParam);
    }

    @PostMapping("/{taskNo}/regenerate")
    public ResponseEntity<Result<?>> regenerateReport(
            @PathVariable String taskNo,
            @RequestParam(value = "async", required = false) Boolean asyncParam,
            @RequestBody(required = false) Map<String, Object> options) {
        log.info("重新生成分析报告: taskNo={}, async={}, options={}", taskNo, asyncParam, options);
        return handleReportRequest(taskNo, true, asyncParam);
    }

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
