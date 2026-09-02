package com.geo.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 【分析服务线程池配置】
 * 设计思路：
 * 1. 单独为分析服务分配线程池，与geo-task-service的线程池参数不同（分析更偏CPU计算）
 * 2. 参数调优：
 *    - corePoolSize=8 / maxPoolSize=16：分析是CPU密集型任务，核心线程数约等于CPU核数
 *    - queueCapacity=500：排队容量，防止任务过多导致OOM
 *    - keepAliveSeconds=60：非核心线程空闲存活时间
 *    - CallerRunsPolicy拒绝策略：队列满了后，让提交任务的线程自己同步执行（避免任务丢失，同时形成自然背压）
 * 3. shutdown策略：设置WaitForTasksToCompleteOnShutdown=true，服务停止时等待正在执行的报告完成（最多等60秒）
 */
@Configuration
public class ThreadPoolConfig {

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
}