package com.geo;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * geo-rpa-service 启动类，本微服务的职责是「RPA 任务池分发」：维护按「问题 x AI 平台」
 * 拆出的执行单元，供各平台的 worker 脚本通过 HTTP 认领执行并回报结果。
 *
 * 关键注解：
 *   - @MapperScan("com.geo.mapper")：扫描 MyBatis Mapper 接口，生成可注入的数据访问 Bean；
 *   - @EnableScheduling：启用 @Scheduled 定时任务（租约过期清扫、卡住巡检通知）；
 *   - @EnableAsync：启用 @Async 异步执行能力（配合 ThreadPoolConfig 的线程池）。
 */
@SpringBootApplication
@MapperScan("com.geo.mapper")
@EnableAsync
@EnableScheduling
public class GeoRpaServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(GeoRpaServiceApplication.class, args);
    }
}
