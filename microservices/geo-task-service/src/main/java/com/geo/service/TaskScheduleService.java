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

/**
 * 【周期任务调度服务】
 *
 * 设计思路：
 * 用户可以把任务设置成"每天/每周自动跑一次"（日报、周报）。
 * 这个类负责把"下次执行时间"注册到 Spring TaskScheduler 里，到点了就触发任务重跑。
 *
 * ============= 核心机制 =============
 *
 * 1. 内存调度 + 数据库兜底（双重保险）：
 *    a) 【内存层】每次保存任务时，调用 scheduleTask() 把这个任务注册到 JVM 的调度线程池。
 *        → 优点：到点立即触发，精确到秒级，不频繁查库。
 *        → 缺点：服务重启内存调度就没了。
 *    b) 【数据库兜底】每小时跑一次 fallbackScan()，从DB里把"本该触发但没触发的任务"找出来补触发。
 *        → 优点：重启后不会丢；就算内存调度失效也不会漏。
 *        → 缺点：最多延迟1小时才被扫到。
 *    两者结合：平时走内存精确触发，万一失效也有兜底。
 *
 * 2. 生命周期管理：
 *    - @PostConstruct init()：服务启动时，从DB里把所有待执行的周期任务重新注册一遍。
 *    - @PreDestroy destroy()：服务关闭时，取消所有已注册的Future，避免重复执行。
 *    - scheduledFutures：ConcurrentHashMap保存 taskNo → 调度Future，支持按taskNo取消。
 *
 * 3. scheduleTask的"先取消再注册"：
 *    用户修改了下次执行时间，需要先把旧的调度取消，不然会触发两次。所以每次先cancelTask再注册新的。
 */
@Service
public class TaskScheduleService {

    private static final Logger log = LoggerFactory.getLogger(TaskScheduleService.class);

    private final TaskService taskService;
    private final TaskMapper taskMapper;
    private final TaskScheduler taskScheduler;

    /**
     * 内存中保存已注册的调度Future。key=taskNo，value=可取消的调度句柄。
     * ConcurrentHashMap：调度线程和HTTP线程都会读写，必须线程安全。
     */
    private final Map<String, ScheduledFuture<?>> scheduledFutures = new ConcurrentHashMap<>();

    public TaskScheduleService(TaskService taskService, TaskMapper taskMapper, TaskScheduler taskScheduler) {
        this.taskService = taskService;
        this.taskMapper = taskMapper;
        this.taskScheduler = taskScheduler;
    }

    /**
     * 【启动时初始化】把DB里所有待执行的周期任务重新注册到内存调度器。
     * 解决"服务重启后内存调度丢失"的问题。
     */
    @PostConstruct
    public void init() {
        rescheduleAllDueTasks();
        log.info("周期任务调度器初始化完成，已注册 {} 个待执行任务", scheduledFutures.size());
    }

    /**
     * 【关闭时清理】取消全部任务，防止JVM关闭过程中触发
     */
    @PreDestroy
    public void destroy() {
        for (ScheduledFuture<?> future : scheduledFutures.values()) {
            future.cancel(false);
        }
        scheduledFutures.clear();
    }

    /**
     * 【注册/更新单个任务的调度】
     *
     * 执行流程：
     * ① 先取消旧的调度（防止用户改了时间，旧调度还在）
     * ② 没有设置nextRunTime？→ 单次任务，不用调度
     * ③ executionFrequency=single？→ 用户选的是"只跑一次"，不用调度
     * ④ nextRunTime已经过了？→ 留给兜底扫描去处理，内存里不注册过去的时间
     * ⑤ 都通过了 → 调taskScheduler.schedule注册一次性定时任务
     *
     * 注：TaskScheduler.schedule是"到某个具体时间点执行一次"。
     * 周期任务的下一次时间由TaskService.rescheduleDueSingleTask在执行完后自己计算并重新schedule。
     */
    public void scheduleTask(Task task) {
        if (task == null || task.getTaskNo() == null) return;
        // 先取消旧的（关键！防止同一个任务注册了两个调度）
        cancelTask(task.getTaskNo());

        LocalDateTime nextRunTime = task.getNextRunTime();
        if (nextRunTime == null) return;

        String freq = task.getExecutionFrequency();
        if (freq == null || "single".equals(freq)) return; // 单次任务不需要调度

        if (nextRunTime.isBefore(LocalDateTime.now())) return; // 过去的时间不注册

        Date startTime = Date.from(nextRunTime.atZone(ZoneId.systemDefault()).toInstant());
        ScheduledFuture<?> future = taskScheduler.schedule(() -> executeScheduledTask(task.getTaskNo()), startTime);
        scheduledFutures.put(task.getTaskNo(), future);

        long seconds = Duration.between(LocalDateTime.now(), nextRunTime).getSeconds();
        log.info("已注册周期任务调度: taskNo={}, frequency={}, nextRunTime={}, 剩余 {} 秒",
                task.getTaskNo(), freq, nextRunTime, seconds);
    }

    /**
     * 【取消某个任务的内存调度】
     * 用户暂停了任务、删除了任务、或者修改下次执行时间时调用。
     */
    public void cancelTask(String taskNo) {
        if (taskNo == null) return;
        ScheduledFuture<?> future = scheduledFutures.remove(taskNo);
        if (future != null) {
            future.cancel(false);
            log.debug("已取消周期任务调度: taskNo={}", taskNo);
        }
    }

    /**
     * 【全量重新注册】
     * 从DB里查出所有"有nextRunTime且在未来"的任务，一个个重新注册到内存调度。
     * 用于启动时和兜底扫描后补充注册。
     */
    public void rescheduleAllDueTasks() {
        com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<Task> wrapper =
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<>();
        wrapper.isNotNull("next_run_time");
        wrapper.gt("next_run_time", LocalDateTime.now());
        // 各种"活着"的状态才调度（被CANCELLED/DELETED的不要）
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
                // 某个任务注册失败不影响其他的。打日志就好。
                log.error("注册周期任务失败: taskNo={}", task.getTaskNo(), e);
            }
        }
    }

    /**
     * 【调度触发点】到点了就调用taskService.rescheduleDueSingleTask去克隆+提交任务。
     * 注意：schedule里只注册了"一次"，下次的时间由rescheduleDueSingleTask计算后重新注册。
     */
    private void executeScheduledTask(String taskNo) {
        scheduledFutures.remove(taskNo);
        log.info("触发周期任务调度: taskNo={}", taskNo);
        try {
            taskService.rescheduleDueSingleTask(taskNo);
        } catch (Exception e) {
            log.error("周期任务触发失败: taskNo={}", taskNo, e);
        }
    }

    /**
     * 【兜底定时扫描】默认每小时跑一次。
     * 从DB层查出所有"本该触发但没触发"的任务，交给taskService重新跑。
     * 这是防止内存调度失效（服务重启、线程异常）的保险机制。
     */
    @Scheduled(cron = "${geo.task.schedule.fallback-cron:0 0 * * * ?}")
    public void fallbackScan() {
        try {
            int triggered = taskService.rescheduleDueTasks();
            if (triggered > 0) {
                log.info("[兜底扫描] 触发 {} 个到期周期任务重跑", triggered);
                // 触发完再重新注册一次（因为有些任务的nextRunTime可能刚被更新）
                rescheduleAllDueTasks();
            }
        } catch (Exception e) {
            log.error("[兜底扫描] 周期任务调度异常", e);
        }
    }
}