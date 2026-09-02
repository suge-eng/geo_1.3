package com.geo.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

/**
 * RestTemplate 配置。
 *
 * RestTemplate 是 Spring 提供、用来发起 HTTP 请求的工具类，网关在这里创建
 * 唯一一个共享实例并注册为 Bean，供 ApiProxyController 注入后转发请求。
 *
 * 超时设计：
 *   - 连接超时 10 秒：太久连不上就尽快报错，避免请求堆积；
 *   - 读取超时 600 秒：下游有些分析任务耗时很长（如 RPA 抓取、AI 生成报告），
 *     必须给足时间，否则长任务会被误判为超时。
 */
@Configuration
public class RestTemplateConfig {

    @Bean
    public RestTemplate restTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        // 建立 TCP 连接的最长等待时间（毫秒）
        factory.setConnectTimeout(10000);
        // 连接建立后等待响应数据的最长时间（毫秒），600 秒以容忍长耗时下游任务
        factory.setReadTimeout(600000);
        return new RestTemplate(factory);
    }
}
