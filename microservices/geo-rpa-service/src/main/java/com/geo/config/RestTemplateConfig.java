package com.geo.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

/**
 * 注册一个共享的 RestTemplate Bean，供跨服务 HTTP 调用复用。
 *
 * 本服务内多处需要以 HTTP 调用其它微服务（如回调里通知 task-service 更新进度），
 * 统一注入同一个 RestTemplate 实例即可，避免各处各自 new 导致连接资源分散、难以统一管理。
 */
@Configuration
public class RestTemplateConfig {

    @Bean
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }
}
