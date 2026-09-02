package com.geo.service;

import com.geo.entity.TaskResult;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 分发/推进服务：负责「任务到下游的推进」与「实时进度通知」这一类偏流程性的工作。
 *
 * 与 {@link RpaWorkerDispatcherService} 的分工：
 *   - Dispatcher 管「单元怎么被安全地认领/续约/回收/结束」，关注并发正确性与租约；
 *   - 本类管「任务整体的推进信号」，即把进度/完成事件广播出去，不直接触碰任务池的分配细节。
 *
 * 当前为任务池模式：任务单元由 worker 通过 HTTP 主动认领，不再通过 RabbitMQ 派发，
 * 所以 dispatchTasks 保留为空实现，仅兼容旧的内部调度入口；
 * 进度推送（sendProgress / sendComplete）走 Redis Pub/Sub，由 gateway 订阅后转发 WebSocket 到前端。
 */
@Service
public class RpaDispatchService {

    private static final Logger log = LoggerFactory.getLogger(RpaDispatchService.class);

    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;

    public RpaDispatchService(RedisTemplate<String, String> redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * 旧的「被动派发」入口，如今在任务池模式下已不需要真正派发（worker 会主动认领），
     * 此处仅打印日志说明有 N 个执行单元进入任务池，保留签名以兼容调用方。
     */
    public void dispatchTasks(String taskNo, List<TaskResult> results, String brandName,
                              String productName, List<String> competitors, String executionFrequency, Boolean retryOnFailure) {
        log.info("任务池模式：worker 将主动认领 taskNo={} 的 {} 个执行单元，无需此处派发",
                taskNo, results != null ? results.size() : 0);
    }

    /**
     * 广播任务进度：按任务号拼出专属 Redis 频道（geo:ws:progress:{taskNo}）并发布 JSON，
     * 网关订阅后转发 WebSocket，前端据此实时刷新进度条。
     */
    public void sendProgress(String taskNo, String currentAi, String currentQuestion, double percentage) {
        Map<String, Object> progress = new HashMap<>();
        progress.put("type", "PROGRESS");
        progress.put("taskNo", taskNo);
        progress.put("currentAi", currentAi);
        progress.put("currentQuestion", currentQuestion);
        progress.put("percentage", percentage);
        try {
            String json = objectMapper.writeValueAsString(progress);
            redisTemplate.convertAndSend("geo:ws:progress:" + taskNo, json);
        } catch (JsonProcessingException e) {
            log.error("序列化进度消息失败: taskNo={}", taskNo, e);
        }
    }

    /**
     * 广播任务完成信号，前端收到 COMPLETE 后结束该任务的进度展示。
     */
    public void sendComplete(String taskNo) {
        Map<String, Object> complete = new HashMap<>();
        complete.put("type", "COMPLETE");
        complete.put("taskNo", taskNo);
        try {
            String json = objectMapper.writeValueAsString(complete);
            redisTemplate.convertAndSend("geo:ws:progress:" + taskNo, json);
        } catch (JsonProcessingException e) {
            log.error("序列化完成消息失败: taskNo={}", taskNo, e);
        }
    }
}