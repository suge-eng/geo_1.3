package com.geo.service;

import com.geo.entity.Task;
import com.geo.mapper.TaskMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

@Service
public class TaskScheduleService {

    private static final Logger log = LoggerFactory.getLogger(TaskScheduleService.class);

    private final TaskService taskService;
    private final TaskMapper taskMapper;
    private final TaskScheduler taskScheduler;

    private final Map<String, ScheduledFuture<?>> scheduledFutures = new ConcurrentHashMap<>();

    public TaskScheduleService(TaskService taskService, TaskMapper taskMapper, TaskScheduler taskScheduler) {
        this.taskService = taskService;
        this.taskMapper = taskMapper;
        this.taskScheduler = taskScheduler;
    }

    @PostConstruct
    public void init() {
        rescheduleAllDueTasks();
        log.info("周期任务调度器初始化完成，已注册 {} 个待执行任务", scheduledFutures.size());
    }

    @PreDestroy
    public void destroy() {
        for (ScheduledFuture<?> future : scheduledFutures.values()) {
            future.cancel(false);
        }
        scheduledFutures.clear();
    }

    public void scheduleTask(Task task) {
        if (task == null || task.getTaskNo() == null) {
            return;
        }
        cancelTask(task.getTaskNo());

        LocalDateTime nextRunTime = task.getNextRunTime();
        if (nextRunTime == null) {
            return;
        }

        String freq = task.getExecutionFrequency();
        if (freq == null || "single".equals(freq)) {
            return;
        }

        if (nextRunTime.isBefore(LocalDateTime.now())) {
            return;
        }

        Date startTime = Date.from(nextRunTime.atZone(ZoneId.systemDefault()).toInstant());
        ScheduledFuture<?> future = taskScheduler.schedule(() -> executeScheduledTask(task.getTaskNo()), startTime);
        scheduledFutures.put(task.getTaskNo(), future);

        long seconds = Duration.between(LocalDateTime.now(), nextRunTime).getSeconds();
        log.info("已注册周期任务调度: taskNo={}, frequency={}, nextRunTime={}, 剩余 {} 秒",
                task.getTaskNo(), freq, nextRunTime, seconds);
    }

    public void cancelTask(String taskNo) {
        if (taskNo == null) return;
        ScheduledFuture<?> future = scheduledFutures.remove(taskNo);
        if (future != null) {
            future.cancel(false);
            log.debug("已取消周期任务调度: taskNo={}", taskNo);
        }
    }

    public void rescheduleAllDueTasks() {
        com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<Task> wrapper =
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<>();
        wrapper.isNotNull("next_run_time");
        wrapper.gt("next_run_time", LocalDateTime.now());
        wrapper.in("status",
                com.geo.enums.TaskStatus.COMPLETED.name(),
                com.geo.enums.TaskStatus.PARTIAL_FAILED.name(),
                com.geo.enums.TaskStatus.FAILED.name(),
                com.geo.enums.TaskStatus.PROCESSING.name(),
                com.geo.enums.TaskStatus.PENDING.name());
        List<Task> tasks = taskMapper.selectList(wrapper);
        if (tasks == null) return;

        for (Task task : tasks) {
            try {
                scheduleTask(task);
            } catch (Exception e) {
                log.error("注册周期任务失败: taskNo={}", task.getTaskNo(), e);
            }
        }
    }

    private void executeScheduledTask(String taskNo) {
        scheduledFutures.remove(taskNo);
        log.info("触发周期任务调度: taskNo={}", taskNo);
        try {
            taskService.rescheduleDueSingleTask(taskNo);
        } catch (Exception e) {
            log.error("周期任务触发失败: taskNo={}", taskNo, e);
        }
    }

    @Scheduled(cron = "${geo.task.schedule.fallback-cron:0 0 * * * ?}")
    public void fallbackScan() {
        try {
            int triggered = taskService.rescheduleDueTasks();
            if (triggered > 0) {
                log.info("[兜底扫描] 触发 {} 个到期周期任务重跑", triggered);
                rescheduleAllDueTasks();
            }
        } catch (Exception e) {
            log.error("[兜底扫描] 周期任务调度异常", e);
        }
    }
}
