package com.geo;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * 【分析报告服务启动类】
 * 设计思路：
 * 1. 这是geo-analysis-service微服务的入口，负责启动Spring Boot容器
 * 2. 核心职责：
 *    - 分析任务结果数据，生成品牌竞争力分析报告
 *    - 可选调用LLM（DeepSeek等）做AI增强分析（情感判断、品牌排名提取）
 *    - 生成历史报告快照，支持报告版本回溯
 * 3. 注解说明：
 *    - @MapperScan：扫描geo-common模块中共享的Mapper接口
 *    - @EnableAsync：开启异步报告生成（大任务报告耗时较长，用后台线程池处理）
 */
@SpringBootApplication
@MapperScan("com.geo.mapper")
@EnableAsync
public class GeoAnalysisServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(GeoAnalysisServiceApplication.class, args);
    }
}