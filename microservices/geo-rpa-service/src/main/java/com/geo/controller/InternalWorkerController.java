package com.geo.controller;

import com.geo.common.BusinessException;
import com.geo.common.Result;
import com.geo.entity.AiAccount;
import com.geo.entity.TaskResult;
import com.geo.service.AccountPoolService;
import com.geo.service.RpaWorkerDispatcherService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * worker 脚本专用接口（认领 + 账号池）。
 *
 * 三个核心模块：
 *   1. 认领单元：/assign
 *      worker 来领一个待执行的 "1 问题 x 1 AI" 单元。可选传当前绑定的 accountId，
 *      服务端把账号记录到 task_result.account_id 里，后续回调时能知道用哪个账号执行的。
 *
 *   2. 账号池：/acquire-account + /release-account
 *      worker 启动时先借一个账号 → 用这个账号连跑 BATCH_SIZE（如 10）个问题 →
 *      用完释放 → 重新借下一个。这样一个账号不会被多 worker 同时用，每个账号每天跑不超额度。
 *
 *   3. 心跳 & 中止：/heartbeat + /abort
 *      跑着心跳续约（租约过期自动回收），异常中止主动归还。
 *
 * 所有接口都走 /internal/rpa/worker 前缀，网关不做登录态鉴权。
 */
@RestController
@RequestMapping("/internal/rpa/worker")
public class InternalWorkerController {

    private static final Logger log = LoggerFactory.getLogger(InternalWorkerController.class);

    private final RpaWorkerDispatcherService dispatcher;
    private final AccountPoolService accountPoolService;

    public InternalWorkerController(RpaWorkerDispatcherService dispatcher, AccountPoolService accountPoolService) {
        this.dispatcher = dispatcher;
        this.accountPoolService = accountPoolService;
    }

    // ==================== 1. 认领单元 ====================

    /**
     * 认领一个待执行单元。
     *
     * 请求体:
     *   {"platform":"doubao", "workerId":"DESKTOP-ABC"}
     *   或带账号: {"platform":"doubao", "workerId":"DESKTOP-ABC", "accountId":123}
     *
     * 成功返回 data = {id, taskNo, aiPlatform, questionText, accountId}；
     * 没有可执行单元时返回 data = null（worker 应稍后重试）。
     */
    @PostMapping("/assign")
    public Result<TaskResult> assign(@RequestBody Map<String, Object> body) {
        String platform = body.get("platform") != null ? body.get("platform").toString() : null;
        String workerId = body.get("workerId") != null ? body.get("workerId").toString() : "unknown";
        Long accountId = body.get("accountId") != null ? ((Number) body.get("accountId")).longValue() : null;

        if (platform == null || platform.isEmpty()) {
            return Result.fail(400, "platform 不能为空");
        }
        TaskResult unit = dispatcher.claimUnit(platform, workerId, accountId);
        return Result.success(unit);
    }

    // ==================== 2. 账号池（借/还/标记冷却） ====================

    /**
     * 从账号池借一个空闲账号。
     *
     * 请求体: {"platform":"doubao", "workerId":"DESKTOP-ABC"}
     *
     * 成功返回 data = {id, platform, accountName, cookie, priority, dailyUsed, dailyLimit};
     * 没有可用账号时返回失败（worker 应等待后重试）。
     */
    @PostMapping("/acquire-account")
    public Result<Map<String, Object>> acquireAccount(@RequestBody Map<String, Object> body) {
        String platform = body.get("platform") != null ? body.get("platform").toString() : null;
        String workerId = body.get("workerId") != null ? body.get("workerId").toString() : "unknown";

        if (platform == null || platform.isEmpty()) {
            return Result.fail(400, "platform 不能为空");
        }

        try {
            AiAccount account = accountPoolService.acquireAccount(platform, workerId);
            Map<String, Object> data = new HashMap<>();
            data.put("id", account.getId());
            data.put("platform", account.getPlatform());
            data.put("accountName", account.getAccountName());
            data.put("cookie", account.getCookie());
            data.put("priority", account.getPriority());
            data.put("dailyUsed", account.getDailyUsed());
            data.put("dailyLimit", account.getDailyLimit());
            data.put("batchCount", account.getBatchCount());
            return Result.success(data);
        } catch (BusinessException e) {
            log.warn("worker[{}] 借账号失败: platform={}, msg={}", workerId, platform, e.getMessage());
            return Result.fail(429, e.getMessage());
        }
    }

    /**
     * 释放账号。worker 用完 N 个问题后（或遇到风控想主动换号时）调用。
     *
     * 请求体: {"accountId":123, "workerId":"DESKTOP-ABC"}
     */
    @PostMapping("/release-account")
    public Result<Boolean> releaseAccount(@RequestBody Map<String, Object> body) {
        Long accountId = body.get("accountId") != null ? ((Number) body.get("accountId")).longValue() : null;
        String workerId = body.get("workerId") != null ? body.get("workerId").toString() : "unknown";

        if (accountId == null) {
            return Result.fail(400, "accountId 不能为空");
        }
        boolean ok = accountPoolService.releaseAccount(accountId, workerId);
        return Result.success(ok);
    }

    /**
     * 标记账号冷却（worker 遇到验证码/风控时主动调，冷却 N 分钟后自动恢复）。
     *
     * 请求体: {"accountId":123, "workerId":"DESKTOP-ABC", "minutes":10}
     * minutes 不传默认 10。
     */
    @PostMapping("/cooldown-account")
    public Result<String> cooldownAccount(@RequestBody Map<String, Object> body) {
        Long accountId = body.get("accountId") != null ? ((Number) body.get("accountId")).longValue() : null;
        String workerId = body.get("workerId") != null ? body.get("workerId").toString() : "unknown";
        int minutes = body.get("minutes") != null ? ((Number) body.get("minutes")).intValue() : 10;

        if (accountId == null) {
            return Result.fail(400, "accountId 不能为空");
        }
        accountPoolService.markAccountCooldown(accountId, workerId, minutes);
        return Result.success("账号已标记冷却 " + minutes + " 分钟");
    }

    // ==================== 3. 心跳 & 中止 ====================

    /**
     * worker 心跳续约。只有该单元的当前持有者才能续约。
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
     * worker 异常中止，归还单元。
     */
    @PostMapping("/abort")
    public Result<Boolean> abort(@RequestBody Map<String, Object> body) {
        Long unitId = body.get("unitId") != null ? ((Number) body.get("unitId")).longValue() : null;
        String workerId = body.get("workerId") != null ? body.get("workerId").toString() : "unknown";
        dispatcher.abort(unitId, workerId);
        return Result.success(true);
    }
}
