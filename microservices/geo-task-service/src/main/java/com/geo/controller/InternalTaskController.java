package com.geo.controller;

import com.geo.common.Result;
import com.geo.service.TaskService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

/**
 * 仅供微服务内部调用的接口。不对外暴露（网关不转发 /internal/** 路径）
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
     * 触发某个 taskId 的进度刷新与状态检查
     * 供 geo-rpa-service 在 RPA 回调后调用
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
