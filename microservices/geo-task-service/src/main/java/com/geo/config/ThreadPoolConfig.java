package com.geo.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 【线程池配置】
 *
 * 设计思路：
 * 不要直接new Thread，要用线程池！
 * 好处：1. 线程复用（避免频繁创建销毁开销）；2. 限制并发（防系统过载）。
 *
 * 这里配了三个线程池，各司其职（隔离原则：不同类型任务放不同线程池，不会互相影响）：
 *
 * 1. taskExecutor：一般业务异步任务（提交任务后异步拆分、生成结果行等）
 *    - 核心8线程，最大16线程，队列500。业务量适中。
 *
 * 2. rpaDispatchExecutor：专门用来调RPA派发接口
 *    - RPA调用可能很慢，单独放一个池，不阻塞普通业务线程。
 *
 * 3. taskScheduler：定时任务调度池（@Scheduled的定时任务都在这里跑）
 *    - 所有的超时检查、报告生成、周期任务触发，都是定时任务。
 *
 * RejectedExecutionHandler策略：CallerRunsPolicy = 队列满了就让提交的那个线程自己去跑。
 * 这样可以"温柔限流"，不会丢任务，只是会变慢（比直接AbortPolicy抛异常好，因为业务不允许丢任务）。
 */
@Configuration
public class ThreadPoolConfig {

    /**
     * 通用业务线程池：处理任务提交/拆分/生成报告等CPU密集+轻IO操作
     */
    @Bean("taskExecutor")
    public Executor taskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(8);
        executor.setMaxPoolSize(16);
        executor.setQueueCapacity(500);
        executor.setKeepAliveSeconds(60);
        executor.setThreadNamePrefix("task-");
        // 拒绝策略：队列满了，调用者线程自己去执行（不会丢任务）
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        // 应用关闭时，等任务跑完再关闭JVM（防止跑到一半被杀掉）
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        executor.initialize();
        return executor;
    }

    /**
     * RPA派发专用线程池：单独隔离，防止RPA慢调用占满业务线程
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
     * 定时任务调度线程池：跑@Scheduled注解的任务。
     * 配了ErrorHandler：定时任务抛异常时不能白抛，至少打条错误日志。
     */
    @Bean
    public TaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(8);
        scheduler.setThreadNamePrefix("task-schedule-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(60);
        // 定时任务出错不能静默吞掉，打个日志方便排错
        scheduler.setErrorHandler(t -> org.slf4j.LoggerFactory.getLogger(ThreadPoolConfig.class)
                .error("TaskScheduler 线程异常", t));
        scheduler.initialize();
        return scheduler;
    }
}