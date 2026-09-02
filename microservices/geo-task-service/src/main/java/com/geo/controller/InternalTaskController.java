package com.geo.controller;

import com.geo.common.Result;
import com.geo.service.TaskService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

/**
 * 【微服务内部接口】
 *
 * 为什么要和对外接口（TaskController）分开成两个 Controller？
 * 1. 安全隔离：对外接口走 API 网关 + 用户鉴权，任何人都能调到；而 /internal/** 这类接口
 *    是给其他微服务（如 geo-rpa-service）在 RPA 回调时调的，网关不转发、外部访问不到，
 *    从而避免外部用户直接调用内部接口篡改任务状态。
 * 2. 职责边界清晰：对外接口面向"用户操作"（建任务、查进度、重试等），内部接口面向
 *    "服务间协作"（RPA 完成后通知本服务刷新进度）。两者分开写、各自演进互不影响。
 * 3. 鉴权简化：服务间默认可信，内部接口可以少一层用户级鉴权，只做必要的异常兜底。
 */
@RestController
@RequestMapping("/internal/task")
public class InternalTaskController {

    private static final Logger log = LoggerFactory.getLogger(InternalTaskController.class);

    private final TaskService taskService;

    public InternalTaskController(TaskService taskService) {
        this.taskService = taskService;
    }

    /**
     * 触发某个 taskId 的进度刷新与状态检查，供 geo-rpa-service 在 RPA 回调后调用。
     * 为什么需要这条回调通道：RPA Worker 每完成一条子任务，就通过这里通知本服务
     * "重新统计该任务的进度"，这样任务的 totalCount/completedCount/终态 才能实时更新，
     * 而不是等用户在页面上主动刷新时才去兜底计算。
     */
    @PostMapping("/{taskId}/updateProgress")
    public Result<Void> updateTaskProgress(@PathVariable Long taskId) {
        try {
            taskService.updateTaskProgress(taskId);
            return Result.success();
        } catch (Exception e) {
            log.error("内部接口刷新任务进度失败: taskId={}", taskId, e);
            return Result.fail(500, "刷新进度失败: " + e.getMessage());
        }
    }
}
