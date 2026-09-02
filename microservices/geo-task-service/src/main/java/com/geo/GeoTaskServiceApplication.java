package com.geo;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 【任务服务启动类】
 *
 * 设计思路：
 * geo-task-service 是系统的核心服务，负责任务管理（增删改查 + 提交 + 调度）。
 *
 * 注解说明：
 * - @SpringBootApplication：Spring Boot启动标记，触发自动配置+组件扫描
 * - @EnableScheduling：开启@Scheduled定时任务（超时检查、周期任务触发等）
 * - @EnableAsync：开启@Async异步方法（提交任务后异步处理，不阻塞HTTP线程）
 * - @MapperScan：MyBatis-Plus扫描Mapper接口位置（因为Mapper在geo-common包里，要显式指定）
 */
@SpringBootApplication
@EnableScheduling
@MapperScan("com.geo.mapper")
@EnableAsync
public class GeoTaskServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(GeoTaskServiceApplication.class, args);
    }
}