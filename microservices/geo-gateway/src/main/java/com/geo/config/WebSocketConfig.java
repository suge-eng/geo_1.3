package com.geo.config;

import com.geo.websocket.ProgressWebSocketHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * WebSocket 配置。
 *
 * 作用：把“浏览器建立 WebSocket 连接的地址”与“处理该连接的 Handler”绑定起来。
 * 前端按 /ws/progress/{任务号} 建立连接，路径中的 {taskNo} 会被 Handler 解析出来，
 * 用于定位“这个连接关心哪个任务的进度”。
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final ProgressWebSocketHandler progressWebSocketHandler;

    public WebSocketConfig(ProgressWebSocketHandler progressWebSocketHandler) {
        this.progressWebSocketHandler = progressWebSocketHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // 注册进度推送的 WebSocket 端点，路径变量 {taskNo} 用来区分不同任务
        registry.addHandler(progressWebSocketHandler, "/ws/progress/{taskNo}")
                // 允许任意来源的跨域连接（前端可能从别的端口/域名打开页面）
                .setAllowedOriginPatterns("*");
    }
}
