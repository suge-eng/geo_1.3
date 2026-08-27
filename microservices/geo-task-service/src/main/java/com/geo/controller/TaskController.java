package com.geo.controller;

import com.geo.common.Result;
import com.geo.dto.ExcelTaskSubmitRequest;
import com.geo.dto.TaskProgressVO;
import com.geo.dto.TaskRankingVO;
import com.geo.dto.TaskResultVO;
import com.geo.dto.TaskSubmitRequest;
import com.geo.entity.Task;
import com.geo.service.ExcelParseService;
import com.geo.service.TaskService;
import jakarta.validation.Valid;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api/task")
public class TaskController {

    private static final Logger log = LoggerFactory.getLogger(TaskController.class);

    private final TaskService taskService;
    private final ExcelParseService excelParseService;

    public TaskController(TaskService taskService, ExcelParseService excelParseService) {
        this.taskService = taskService;
        this.excelParseService = excelParseService;
    }

    @GetMapping("/list")
    public ResponseEntity<Result<List<Task>>> listTasks() {
        List<Task> tasks = taskService.listTasksWithProgressRefresh();
        for (Task task : tasks) {
            enrichTaskFields(task);
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store, no-cache, must-revalidate, max-age=0")
                .header(HttpHeaders.PRAGMA, "no-cache")
                .header(HttpHeaders.EXPIRES, "0")
                .body(Result.success(tasks));
    }

    private void enrichTaskFields(Task task) {
        if (task == null) return;
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

    @PostMapping("/create")
    public Result<Task> createTask(@Valid @RequestBody TaskSubmitRequest request) {
        log.info("收到任务创建请求: aiPlatforms={}, questionCount={}, brandName={}, productName={}, whitelistUrlCount={}",
                request.getAiPlatforms(), request.getQuestions().size(), request.getBrandName(), request.getProductName(),
                request.getWhitelistUrls() != null ? request.getWhitelistUrls().size() : 0);

        Task task = taskService.createTask(
                request.getAiPlatforms(),
                request.getQuestions(),
                request.getTitle(),
                request.getBrandName(),
                request.getProductName(),
                request.getCompetitors(),
                request.getExecutionFrequency(),
                request.getRetryOnFailure(),
                request.getScope(),
                request.getWhitelistUrls()
        );
        enrichTaskFields(task);
        return Result.success("任务创建成功", task);
    }

    @PostMapping(value = "/parse-whitelist", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Result<List<String>> parseWhitelist(@RequestParam("file") MultipartFile file) {
        log.info("收到白名单解析请求: filename={}", file.getOriginalFilename());
        List<String> urls = excelParseService.parseWhitelistFromExcel(file);
        return Result.success(urls);
    }

    @PostMapping("/{taskNo}/submit")
    public Result<Task> submitTask(@PathVariable String taskNo,
                                   @RequestBody Map<String, Object> request) {
        String executionFrequency = (String) request.getOrDefault("executionFrequency", "single");
        Boolean retryOnFailure = (Boolean) request.getOrDefault("retryOnFailure", false);
        log.info("收到任务提交请求: taskNo={}, executionFrequency={}, retryOnFailure={}",
                taskNo, executionFrequency, retryOnFailure);
        Task task = taskService.submitTask(taskNo, executionFrequency, retryOnFailure);
        enrichTaskFields(task);
        return Result.success("任务已提交", task);
    }

    @DeleteMapping("/{taskNo}")
    public Result<Void> deleteTask(@PathVariable String taskNo) {
        log.info("收到任务删除请求: taskNo={}", taskNo);
        taskService.deleteTask(taskNo);
        return Result.success("任务已删除");
    }

    @GetMapping("/{taskNo}/progress")
    public ResponseEntity<Result<TaskProgressVO>> getTaskProgress(@PathVariable String taskNo) {
        TaskProgressVO progress = taskService.getTaskProgress(taskNo);
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store, no-cache, must-revalidate, max-age=0")
                .header(HttpHeaders.PRAGMA, "no-cache")
                .header(HttpHeaders.EXPIRES, "0")
                .body(Result.success(progress));
    }

    @GetMapping("/{taskNo}/results")
    public ResponseEntity<Result<List<TaskResultVO>>> getTaskResults(@PathVariable String taskNo) {
        List<TaskResultVO> results = taskService.getTaskResults(taskNo);
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store, no-cache, must-revalidate, max-age=0")
                .header(HttpHeaders.PRAGMA, "no-cache")
                .header(HttpHeaders.EXPIRES, "0")
                .body(Result.success(results));
    }

    @GetMapping("/{taskNo}/rankings")
    public ResponseEntity<Result<TaskRankingVO>> getTaskRankings(@PathVariable String taskNo) {
        log.info("获取任务品牌排名: taskNo={}", taskNo);
        TaskRankingVO ranking = taskService.getTaskRankings(taskNo);
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store, no-cache, must-revalidate, max-age=0")
                .header(HttpHeaders.PRAGMA, "no-cache")
                .header(HttpHeaders.EXPIRES, "0")
                .body(Result.success(ranking));
    }

    @GetMapping("/{taskNo}")
    public ResponseEntity<Result<Task>> getTask(@PathVariable String taskNo) {
        Task task = taskService.getTaskByNoWithProgressRefresh(taskNo);
        if (task == null) {
            return ResponseEntity.status(404).body(Result.fail(404, "任务不存在"));
        }
        enrichTaskFields(task);
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store, no-cache, must-revalidate, max-age=0")
                .header(HttpHeaders.PRAGMA, "no-cache")
                .header(HttpHeaders.EXPIRES, "0")
                .body(Result.success(task));
    }

    @PostMapping("/{taskNo}/retry")
    public Result<Task> retryFailedTasks(@PathVariable String taskNo) {
        log.info("收到重试失败任务请求: taskNo={}", taskNo);
        Task task = taskService.retryFailedTasks(taskNo);
        enrichTaskFields(task);
        return Result.success("失败任务已重新调度", task);
    }

    @PostMapping("/{taskNo}/retry/all")
    public Result<Task> retryAllTasks(@PathVariable String taskNo) {
        log.info("收到重试所有任务请求: taskNo={}", taskNo);
        Task task = taskService.retryAllTasks(taskNo);
        enrichTaskFields(task);
        return Result.success("所有任务已重新调度", task);
    }

    @PostMapping("/{taskNo}/retry/success")
    public Result<Task> retrySuccessTasks(@PathVariable String taskNo) {
        log.info("收到重试成功任务请求: taskNo={}", taskNo);
        Task task = taskService.retrySuccessTasks(taskNo);
        enrichTaskFields(task);
        return Result.success("成功任务已重新调度", task);
    }

    @PostMapping("/result/{taskResultId}/retry")
    public Result<Task> retrySpecificTaskResult(@PathVariable Long taskResultId) {
        log.info("收到重试单个任务结果请求: taskResultId={}", taskResultId);
        Task task = taskService.retrySpecificTaskResult(taskResultId);
        enrichTaskFields(task);
        return Result.success("任务结果已重新调度", task);
    }

    @DeleteMapping("/result/{taskResultId}")
    public Result<Task> deleteTaskResult(@PathVariable Long taskResultId) {
        log.info("收到删除单个任务结果请求: taskResultId={}", taskResultId);
        Task task = taskService.deleteTaskResult(taskResultId);
        enrichTaskFields(task);
        return Result.success("问题已删除", task);
    }

    @DeleteMapping("/result/batch")
    public Result<Task> deleteTaskResults(@RequestBody List<Long> taskResultIds) {
        log.info("收到批量删除任务结果请求: count={}", taskResultIds != null ? taskResultIds.size() : 0);
        Task task = taskService.deleteTaskResults(taskResultIds);
        enrichTaskFields(task);
        return Result.success("已批量删除选中的问题", task);
    }

    @PostMapping(value = "/create/excel", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Result<Task> createTaskFromExcel(
            @RequestParam("file") MultipartFile file,
            @RequestParam("aiPlatforms") List<String> aiPlatforms,
            @RequestParam("title") String title,
            @RequestParam("brandName") String brandName,
            @RequestParam(value = "productName", required = false) String productName,
            @RequestParam(value = "competitors", required = false) List<String> competitors,
            @RequestParam(value = "executionFrequency", defaultValue = "single") String executionFrequency,
            @RequestParam(value = "retryOnFailure", defaultValue = "false") Boolean retryOnFailure,
            @RequestParam(value = "scope", defaultValue = "LOCAL") String scope,
            @RequestParam(value = "whitelistUrls", required = false) String whitelistUrlsJson) {

        List<String> whitelistUrls = null;
        if (whitelistUrlsJson != null && !whitelistUrlsJson.isEmpty()) {
            try {
                whitelistUrls = new com.fasterxml.jackson.databind.ObjectMapper()
                        .readValue(whitelistUrlsJson, new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {});
            } catch (Exception e) {
                log.warn("解析白名单URLs JSON失败", e);
            }
        }

        log.info("收到Excel任务创建请求: filename={}, aiPlatforms={}, title={}, brandName={}, productName={}, whitelistUrlCount={}",
                file.getOriginalFilename(), aiPlatforms, title, brandName, productName,
                whitelistUrls != null ? whitelistUrls.size() : 0);

        List<String> questions = excelParseService.parseQuestionsFromExcel(file);
        log.info("从Excel解析出 {} 个问题", questions.size());

        Task task = taskService.createTask(
                aiPlatforms,
                questions,
                title,
                brandName,
                productName,
                competitors,
                executionFrequency,
                retryOnFailure,
                scope,
                whitelistUrls
        );
        enrichTaskFields(task);
        return Result.success("Excel任务创建成功", task);
    }

    @GetMapping("/excel-template")
    public ResponseEntity<byte[]> downloadExcelTemplate() {
        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("问题模板");

            Row headerRow = sheet.createRow(0);
            Cell headerCell = headerRow.createCell(0);
            headerCell.setCellValue("询问句");

            CellStyle headerStyle = workbook.createCellStyle();
            Font headerFont = workbook.createFont();
            headerFont.setBold(true);
            headerStyle.setFont(headerFont);
            headerStyle.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            headerCell.setCellStyle(headerStyle);

            Row sampleRow = sheet.createRow(1);
            Cell sampleCell = sampleRow.createCell(0);
            sampleCell.setCellValue("请在此填写你想询问的问题，例如：XX品牌手机怎么样？");

            sheet.setColumnWidth(0, 8000);

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            workbook.write(baos);
            byte[] content = baos.toByteArray();

            String filename = URLEncoder.encode("Excel问题模板.xlsx", StandardCharsets.UTF_8)
                    .replace("+", "%20");

            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            "attachment; filename=\"" + filename + "\"; filename*=UTF-8''" + filename)
                    .contentType(MediaType.parseMediaType(
                            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                    .contentLength(content.length)
                    .body(content);
        } catch (Exception e) {
            log.error("生成Excel模板失败", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @GetMapping("/whitelist-template")
    public ResponseEntity<byte[]> downloadWhitelistTemplate() {
        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("白名单模板");

            Row headerRow = sheet.createRow(0);
            Cell headerCell = headerRow.createCell(0);
            headerCell.setCellValue("网址域名");

            CellStyle headerStyle = workbook.createCellStyle();
            Font headerFont = workbook.createFont();
            headerFont.setBold(true);
            headerStyle.setFont(headerFont);
            headerStyle.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            headerCell.setCellStyle(headerStyle);

            String[] samples = {
                "baidu.com",
                "zhihu.com",
                "weibo.com",
                "163.com",
                "bilibili.com",
                "sina.com.cn",
                "sohu.com",
                "people.com.cn",
                "xinhuanet.com"
            };
            for (int i = 0; i < samples.length; i++) {
                Row sampleRow = sheet.createRow(i + 1);
                Cell sampleCell = sampleRow.createCell(0);
                sampleCell.setCellValue(samples[i]);
            }

            sheet.setColumnWidth(0, 6000);

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            workbook.write(baos);
            byte[] content = baos.toByteArray();

            String filename = URLEncoder.encode("白名单模板.xlsx", StandardCharsets.UTF_8)
                    .replace("+", "%20");

            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            "attachment; filename=\"" + filename + "\"; filename*=UTF-8''" + filename)
                    .contentType(MediaType.parseMediaType(
                            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                    .contentLength(content.length)
                    .body(content);
        } catch (Exception e) {
            log.error("生成白名单模板失败", e);
            return ResponseEntity.internalServerError().build();
        }
    }
}