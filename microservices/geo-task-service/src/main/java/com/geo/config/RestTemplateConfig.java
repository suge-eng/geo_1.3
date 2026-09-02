package com.geo.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

/**
 * 【HTTP客户端配置】
 *
 * 设计思路：
 * RestTemplate是Spring自带的HTTP调用客户端，用来调其他微服务接口。
 * 比如task-service调rpa-service派发任务，就是通过RestTemplate发HTTP请求。
 *
 * 两个超时一定要配（否则默认无限等待会把线程卡死）：
 * - connectTimeout=10s：建立TCP连接最多等10秒，10秒连不上就放弃
 * - readTimeout=120s：连上后最多等对方2分钟返回数据（有些RPA操作比较耗时）
 */
@Configuration
public class RestTemplateConfig {

    /**
     * 创建RestTemplate Bean。设了合理的超时时间防止线程永久阻塞。
     */
    @Bean
    public RestTemplate restTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10000);
        factory.setReadTimeout(120000);
        return new RestTemplate(factory);
    }
}