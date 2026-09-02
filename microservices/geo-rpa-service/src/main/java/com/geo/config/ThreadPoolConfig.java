package com.geo.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 线程池 / 定时任务调度器配置。
 *
 * 分三类线程池，各司其职：
 *   - taskExecutor：通用业务线程池（@Async 等异步逻辑），核心 8、最大 16、队列 500；
 *   - rpaDispatchExecutor：RPA 分发专用，核心 4、最大 8，把 RPA 的 IO 密集型任务与通用任务隔离开，
 *     避免两类负载互相抢占空闲线程；
 *   - taskScheduler：@Scheduled 定时任务（租约清扫、卡住巡检等）共用的调度线程池，池大小 8。
 * 两个执行器均使用 CallerRunsPolicy：任务满载时由调用方线程直接执行，宁可调用方短暂变慢，
 * 也不静默丢弃任务，保证关键逻辑最终一定能被执行。
 */
@Configuration
public class ThreadPoolConfig {

    /**
     * 通用业务线程池，命名 task-*，供 @Async 等异步场景使用。
     * 关闭时等待在途任务完成（最多 60 秒），避免服务器停机时任务被硬性中止。
     */
    @Bean("taskExecutor")
    public Executor taskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(8);
        executor.setMaxPoolSize(16);
        executor.setQueueCapacity(500);
        executor.setKeepAliveSeconds(60);
        executor.setThreadNamePrefix("task-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        executor.initialize();
        return executor;
    }

    /**
     * RPA 分发专用线程池，命名 rpa-dispatch-*，隔离 RPA 任务与通用异步任务的资源。
     */
    @Bean("rpaDispatchExecutor")
    public Executor rpaDispatchExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(200);
        executor.setKeepAliveSeconds(60);
        executor.setThreadNamePrefix("rpa-dispatch-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }

    /**
     * 定时任务调度线程池，所有 @Scheduled 方法共享；配置 ErrorHandler 统一记录任务抛出的异常，避免异常吞掉后无从排查。
     */
    @Bean
    public TaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(8);
        scheduler.setThreadNamePrefix("task-schedule-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(60);
        scheduler.setErrorHandler(t -> org.slf4j.LoggerFactory.getLogger(ThreadPoolConfig.class)
                .error("TaskScheduler 线程异常", t));
        scheduler.initialize();
        return scheduler;
    }
}
