package com.geo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * geo-gateway 的启动类。
 *
 * 本项目名为“geo-gateway”，是整个 AI 品牌分析系统的唯一对外入口：
 *   - 用 ApiProxyController 把前端的 HTTP 请求转发到各微服务；
 *   - 用 WebSocket + Redis 发布订阅，把任务进度实时推送到前端页面。
 *
 * @SpringBootApplication 会开启自动配置、组件扫描，并在 main 中启动内嵌的
 * Tomcat 服务器，随后加载本类所在包下的所有 Bean（控制器、配置等）。
 */
@SpringBootApplication
public class GeoGatewayApplication {
    public static void main(String[] args) {
        SpringApplication.run(GeoGatewayApplication.class, args);
    }
}
