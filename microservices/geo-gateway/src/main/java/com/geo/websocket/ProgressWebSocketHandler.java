package com.geo.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class ProgressWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(ProgressWebSocketHandler.class);

    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final Map<String, Map<String, WebSocketSession>> TASK_SESSIONS = new ConcurrentHashMap<>();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        String taskNo = extractTaskNo(session);
        if (taskNo != null) {
            TASK_SESSIONS.computeIfAbsent(taskNo, k -> new ConcurrentHashMap<>())
                    .put(session.getId(), session);
            log.info("WebSocket连接建立: taskNo={}, sessionId={}", taskNo, session.getId());
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String taskNo = extractTaskNo(session);
        if (taskNo != null) {
            Map<String, WebSocketSession> sessions = TASK_SESSIONS.get(taskNo);
            if (sessions != null) {
                sessions.remove(session.getId());
                if (sessions.isEmpty()) {
                    TASK_SESSIONS.remove(taskNo);
                }
            }
            log.info("WebSocket连接关闭: taskNo={}, sessionId={}", taskNo, session.getId());
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        log.debug("收到WebSocket消息: {}", message.getPayload());
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.error("WebSocket传输错误: sessionId={}", session.getId(), exception);
        try {
            session.close();
        } catch (Exception e) {
            log.error("关闭WebSocket连接失败", e);
        }
    }

    public void sendProgress(String taskNo, Object message) {
        Map<String, WebSocketSession> sessions = TASK_SESSIONS.get(taskNo);
        if (sessions == null || sessions.isEmpty()) {
            return;
        }
        try {
            String json = objectMapper.writeValueAsString(message);
            TextMessage textMessage = new TextMessage(json);
            for (WebSocketSession session : sessions.values()) {
                if (session.isOpen()) {
                    synchronized (session) {
                        session.sendMessage(textMessage);
                    }
                }
            }
        } catch (Exception e) {
            log.error("推送WebSocket消息失败: taskNo={}", taskNo, e);
        }
    }

    public void sendComplete(String taskNo) {
        Map<String, WebSocketSession> sessions = TASK_SESSIONS.get(taskNo);
        if (sessions == null || sessions.isEmpty()) {
            return;
        }
        try {
            Map<String, String> msg = Map.of("type", "COMPLETE", "taskNo", taskNo);
            String json = objectMapper.writeValueAsString(msg);
            TextMessage textMessage = new TextMessage(json);
            for (WebSocketSession session : sessions.values()) {
                if (session.isOpen()) {
                    synchronized (session) {
                        session.sendMessage(textMessage);
                    }
                }
            }
        } catch (Exception e) {
            log.error("推送完成通知失败: taskNo={}", taskNo, e);
        }
    }

    private String extractTaskNo(WebSocketSession session) {
        String path = session.getUri().getPath();
        if (path != null && path.contains("/ws/progress/")) {
            return path.substring(path.lastIndexOf('/') + 1);
        }
        return null;
    }
}
