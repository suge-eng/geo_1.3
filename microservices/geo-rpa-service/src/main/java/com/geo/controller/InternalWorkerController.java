package com.geo.controller;

import com.geo.common.Result;
import com.geo.entity.TaskResult;
import com.geo.service.RpaWorkerDispatcherService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * worker 脚本专用认领接口。
 *
 * 各平台 worker 通过 HTTP 认领一个单元 -> 执行 -> 回调 -> 再认领，替代原先依赖
 * RabbitMQ 单队列 + SQLite 全局锁的串行消费方式，从而实现多用户公平调度与多机并行。
 */
@RestController
@RequestMapping("/internal/rpa/worker")
public class InternalWorkerController {

    private static final Logger log = LoggerFactory.getLogger(InternalWorkerController.class);

    private final RpaWorkerDispatcherService dispatcher;

    public InternalWorkerController(RpaWorkerDispatcherService dispatcher) {
        this.dispatcher = dispatcher;
    }

    /**
     * 认领一个待执行单元。
     *
     * 请求体: {"platform":"deepseek","workerId":"DESKTOP-ABC"}
     * 成功且有待执行单元时返回 data = {id, taskNo, aiPlatform, questionText}；
     * 当前没有该平台的可执行单元时返回 data = null（worker 应稍后重试）。
     */
    @PostMapping("/assign")
    public Result<TaskResult> assign(@RequestBody Map<String, Object> body) {
        String platform = body.get("platform") != null ? body.get("platform").toString() : null;
        String workerId = body.get("workerId") != null ? body.get("workerId").toString() : "unknown";
        if (platform == null || platform.isEmpty()) {
            return Result.fail(400, "platform 不能为空");
        }
        TaskResult unit = dispatcher.claimUnit(platform, workerId);
        return Result.success(unit);
    }

    /**
     * worker 心跳续约。请求体: {"unitId":123,"workerId":"DESKTOP-ABC"}
     */
    @PostMapping("/heartbeat")
    public Result<Boolean> heartbeat(@RequestBody Map<String, Object> body) {
        Long unitId = body.get("unitId") != null ? ((Number) body.get("unitId")).longValue() : null;
        String workerId = body.get("workerId") != null ? body.get("workerId").toString() : "unknown";
        boolean ok = dispatcher.heartbeat(unitId, workerId);
        if (!ok) {
            return Result.fail(409, "单元已不属于当前 worker 或已结束，请中止并重新认领");
        }
        return Result.success(true);
    }

    /**
     * worker 异常中止，归还单元。请求体: {"unitId":123,"workerId":"DESKTOP-ABC"}
     */
    @PostMapping("/abort")
    public Result<Boolean> abort(@RequestBody Map<String, Object> body) {
        Long unitId = body.get("unitId") != null ? ((Number) body.get("unitId")).longValue() : null;
        String workerId = body.get("workerId") != null ? body.get("workerId").toString() : "unknown";
        dispatcher.abort(unitId, workerId);
        return Result.success(true);
    }
}