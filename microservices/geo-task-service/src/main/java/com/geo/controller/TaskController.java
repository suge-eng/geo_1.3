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

/**
 * 【对外任务接口】
 *
 * 设计思路：
 * 这是暴露给前端/API网关的 HTTP 接口，路径统一以 /api/task 开头。
 * 本层只做"参数接收→调用 Service→组装返回"，具体的任务拆分/调度/统计逻辑都在 Service 层。
 *
 * 为什么所有"查询类"接口都手动加"禁止缓存"响应头（no-store/no-cache）？
 * 任务的进度、结果会随 RPA 回调实时变化，如果浏览器或网关把响应缓存了，
 * 用户会反复看到过期的旧进度（比如一直显示"进行中"）。所以查询接口统一
 * 加禁用缓存头，保证每次请求都真正打到后端、拿到最新数据。
 *
 * 为什么 create 和 submit 被拆成两个接口（而不是一步到位）？
 * create 只负责在数据库里"生成"任务（状态为 PENDING，尚未执行）；
 * submit 才真正把任务派发给 RPA 执行。拆开的好处：
 * 用户可以"先创建、预览确认、再提交"，也方便 Excel 批量导入场景（先统一创建再统一提交）。
 */
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

    /**
     * 查询全部任务列表（按创建时间倒序）。
     * 列表页展示的字段里有一些是老版本没有的，先 enrich 回填默认值再返回，避免前端拿到 null。
     */
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

    /**
     * 回填老版本数据缺失字段的默认值。
     * 为什么这么做：scope/questionCount/intentCount 是后来新增的字段，历史数据里可能是 null，
     * 直接返回 null 会让前端做空判断时出问题。这里只在"返回给前端前"补默认值，不改动数据库，
     * 避免为了一批老数据做繁琐的数据迁移。
     */
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

    /**
     * 创建任务（JSON 入参，由前端表单直接提交）。
     * 只创建、不派发——任务此时是 PENDING 状态，需再调 submit 接口才会真正执行，详见 submitTask。
     */
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

    /**
     * 解析白名单 Excel。为什么要在"创建任务前"单独提供这个接口？
     * 让用户先上传白名单文件→后端解析并返回规范化后的域名列表→前端展示预览，
     * 用户确认无误后再连同正式创建任务一起提交，避免"直接创建后才发现白名单解析结果不对"。
     */
    @PostMapping(value = "/parse-whitelist", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Result<List<String>> parseWhitelist(@RequestParam("file") MultipartFile file) {
        log.info("收到白名单解析请求: filename={}", file.getOriginalFilename());
        List<String> urls = excelParseService.parseWhitelistFromExcel(file);
        return Result.success(urls);
    }

    /**
     * 提交任务：把 PENDING 状态的任务正式派发给 RPA 开始执行。
     * 请求体用 Map 而非强类型 DTO：因为 executionFrequency/retryOnFailure 都是可选参数，
     * 用 Map + getOrDefault 能容忍前端漏传字段，并给出默认值。
     */
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

    /** 删除任务（含子表级联清理，运行中的任务会被 Service 层拒绝）。 */
    @DeleteMapping("/{taskNo}")
    public Result<Void> deleteTask(@PathVariable String taskNo) {
        log.info("收到任务删除请求: taskNo={}", taskNo);
        taskService.deleteTask(taskNo);
        return Result.success("任务已删除");
    }

    /** 查询任务进度（前端进度条轮询用，带禁用缓存头保证实时）。 */
    @GetMapping("/{taskNo}/progress")
    public ResponseEntity<Result<TaskProgressVO>> getTaskProgress(@PathVariable String taskNo) {
        TaskProgressVO progress = taskService.getTaskProgress(taskNo);
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store, no-cache, must-revalidate, max-age=0")
                .header(HttpHeaders.PRAGMA, "no-cache")
                .header(HttpHeaders.EXPIRES, "0")
                .body(Result.success(progress));
    }

    /** 查询任务的结果明细列表（每条"问题×平台"对应一条结果，是调度的最小单位）。 */
    @GetMapping("/{taskNo}/results")
    public ResponseEntity<Result<List<TaskResultVO>>> getTaskResults(@PathVariable String taskNo) {
        List<TaskResultVO> results = taskService.getTaskResults(taskNo);
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store, no-cache, must-revalidate, max-age=0")
                .header(HttpHeaders.PRAGMA, "no-cache")
                .header(HttpHeaders.EXPIRES, "0")
                .body(Result.success(results));
    }

    /** 查询品牌排名分析（从 AI 回答中统计各品牌的提及率、排名分布等）。 */
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

    /**
     * 查询单个任务详情。查不到时返回 404 而不是抛异常，
     * 让前端能区分"接口错误(500)"和"资源不存在(404)"这两种情况。
     */
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

    /** 重试任务中所有失败/超时的子任务（最常用的补救操作）。 */
    @PostMapping("/{taskNo}/retry")
    public Result<Task> retryFailedTasks(@PathVariable String taskNo) {
        log.info("收到重试失败任务请求: taskNo={}", taskNo);
        Task task = taskService.retryFailedTasks(taskNo);
        enrichTaskFields(task);
        return Result.success("失败任务已重新调度", task);
    }

    /** 重试任务的全部子任务（含已成功的，用于模型升级后重新跑一遍做对比）。 */
    @PostMapping("/{taskNo}/retry/all")
    public Result<Task> retryAllTasks(@PathVariable String taskNo) {
        log.info("收到重试所有任务请求: taskNo={}", taskNo);
        Task task = taskService.retryAllTasks(taskNo);
        enrichTaskFields(task);
        return Result.success("所有任务已重新调度", task);
    }

    /** 终止任务：标记 CANCELLED，并把尚未执行的子任务置为失败。 */
    @PostMapping("/{taskNo}/stop")
    public Result<Task> stopTask(@PathVariable String taskNo) {
        log.info("收到终止任务请求: taskNo={}", taskNo);
        Task task = taskService.stopTask(taskNo);
        enrichTaskFields(task);
        return Result.success("任务已终止", task);
    }

    /** 暂停任务：进入 PAUSED，RPA 分发器不再为它认领新的子任务（已跑的不中断）。 */
    @PostMapping("/{taskNo}/pause")
    public Result<Task> pauseTask(@PathVariable String taskNo) {
        log.info("收到暂停任务请求: taskNo={}", taskNo);
        Task task = taskService.pauseTask(taskNo);
        enrichTaskFields(task);
        return Result.success("任务已暂停", task);
    }

    /** 恢复被暂停的任务，回到 PROCESSING 继续被 RPA 认领执行。 */
    @PostMapping("/{taskNo}/resume")
    public Result<Task> resumeTask(@PathVariable String taskNo) {
        log.info("收到恢复任务请求: taskNo={}", taskNo);
        Task task = taskService.resumeTask(taskNo);
        enrichTaskFields(task);
        return Result.success("任务已恢复", task);
    }

    /** 重试已成功的子任务（AI 模型升级后重新生成答案、对比新旧效果）。 */
    @PostMapping("/{taskNo}/retry/success")
    public Result<Task> retrySuccessTasks(@PathVariable String taskNo) {
        log.info("收到重试成功任务请求: taskNo={}", taskNo);
        Task task = taskService.retrySuccessTasks(taskNo);
        enrichTaskFields(task);
        return Result.success("成功任务已重新调度", task);
    }

    /** 重试单条指定的结果（用户在界面上勾选某条不满意的结果单独重跑）。 */
    @PostMapping("/result/{taskResultId}/retry")
    public Result<Task> retrySpecificTaskResult(@PathVariable Long taskResultId) {
        log.info("收到重试单个任务结果请求: taskResultId={}", taskResultId);
        Task task = taskService.retrySpecificTaskResult(taskResultId);
        enrichTaskFields(task);
        return Result.success("任务结果已重新调度", task);
    }

    /** 删除单条结果（正在执行中的会被 Service 层拒绝）。 */
    @DeleteMapping("/result/{taskResultId}")
    public Result<Task> deleteTaskResult(@PathVariable Long taskResultId) {
        log.info("收到删除单个任务结果请求: taskResultId={}", taskResultId);
        Task task = taskService.deleteTaskResult(taskResultId);
        enrichTaskFields(task);
        return Result.success("问题已删除", task);
    }

    /** 批量删除结果，请求体是结果 ID 列表（前端勾选多条后删除）。 */
    @DeleteMapping("/result/batch")
    public Result<Task> deleteTaskResults(@RequestBody List<Long> taskResultIds) {
        log.info("收到批量删除任务结果请求: count={}", taskResultIds != null ? taskResultIds.size() : 0);
        Task task = taskService.deleteTaskResults(taskResultIds);
        enrichTaskFields(task);
        return Result.success("已批量删除选中的问题", task);
    }

    /**
     * 通过 Excel 文件批量创建任务。
     * 为什么用 multipart/form-data 而不是 JSON：要同时上传文件（Excel）和一批表单字段
     * （平台、标题、品牌等），而 HTTP 里文件上传只能走 multipart。
     * 为什么 whitelistUrls 用 JSON 字符串传递：multipart 表单无法直接传 List 结构，
     * 所以约定前端把它序列化成 JSON 字符串，后端再反序列化。
     */
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

    /**
     * 下载问题导入模板。
     * 为什么模板由后端在运行时生成、而不是放一个静态文件？
     * 这样能保证"模板格式"与"ExcelParseService 的解析规则"永远一致，不会出现
     * 模板改了解析逻辑没跟着改的错位问题。
     */
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

    /** 下载白名单导入模板（内置示例域名，方便用户照着格式填写）。 */
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