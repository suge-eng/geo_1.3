package com.geo.controller;

import com.geo.common.Result;
import com.geo.entity.TaskResult;
import com.geo.service.RpaDispatchService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/internal/rpa")
public class InternalRpaController {

    private static final Logger log = LoggerFactory.getLogger(InternalRpaController.class);

    private final RpaDispatchService rpaDispatchService;
    private final ObjectMapper objectMapper;

    public InternalRpaController(RpaDispatchService rpaDispatchService, ObjectMapper objectMapper) {
        this.rpaDispatchService = rpaDispatchService;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/dispatch")
    public Result<String> dispatchTasks(@RequestBody Map<String, Object> body) {
        try {
            String taskNo = (String) body.get("taskNo");
            String brandName = (String) body.get("brandName");
            String productName = (String) body.get("productName");
            String executionFrequency = (String) body.get("executionFrequency");
            Boolean retryOnFailure = body.get("retryOnFailure") != null ? (Boolean) body.get("retryOnFailure") : null;

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> resultsRaw = (List<Map<String, Object>>) body.get("results");
            List<TaskResult> results = new ArrayList<>();
            if (resultsRaw != null) {
                for (Map<String, Object> map : resultsRaw) {
                    TaskResult tr = objectMapper.convertValue(map, TaskResult.class);
                    results.add(tr);
                }
            }

            @SuppressWarnings("unchecked")
            List<String> competitors = (List<String>) body.get("competitors");

            log.info("收到内部调度请求: taskNo={}, resultCount={}, brandName={}, productName={}",
                    taskNo, results.size(), brandName, productName);

            rpaDispatchService.dispatchTasks(taskNo, results, brandName, productName, competitors, executionFrequency, retryOnFailure);

            return Result.success("调度成功");
        } catch (Exception e) {
            log.error("内部调度任务失败", e);
            return Result.fail(500, "调度失败: " + e.getMessage());
        }
    }
}
