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
 * 任务池模式下，任务单元由 worker 通过 HTTP 主动认领，不再通过 RabbitMQ 派发。
 * 此处的 dispatchTasks 保留为空实现以兼容旧的内部调度入口；
 * 进度推送（sendProgress / sendComplete）仍走 Redis Pub/Sub，由 gateway 订阅后转发 WebSocket。
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

    public void dispatchTasks(String taskNo, List<TaskResult> results, String brandName,
                              String productName, List<String> competitors, String executionFrequency, Boolean retryOnFailure) {
        log.info("任务池模式：worker 将主动认领 taskNo={} 的 {} 个执行单元，无需此处派发",
                taskNo, results != null ? results.size() : 0);
    }

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