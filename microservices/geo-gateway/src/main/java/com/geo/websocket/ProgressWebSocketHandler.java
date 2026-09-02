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

/**
 * WebSocket 进度处理器：负责会话管理与进度广播。
 *
 * 核心数据结构 TASK_SESSIONS：
 *   任务号(taskNo) -> { 会话ID(sessionId) -> WebSocketSession }
 * 采用两层 Map，一层按任务号分组，二层按会话 ID 索引。这样当一个任务的进度传来时，
 * 能立刻找到“所有正在关注该任务的浏览器连接”并逐一广播；同时多个浏览器可同时
 * 关注同一任务，互不影响。
 *
 * 使用 ConcurrentHashMap 是因为 Redis 订阅线程会并发调用 sendProgress/sendComplete，
 * 普通 HashMap 在多线程下不安全。
 */
@Component
public class ProgressWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(ProgressWebSocketHandler.class);

    // 用于把进度数据序列化成 JSON 字符串后发送
    private final ObjectMapper objectMapper = new ObjectMapper();

    // 静态全局会话表：所有实例共享。value 的 ConcurrentHashMap 保证同一任务多个会话并发写入安全。
    private static final Map<String, Map<String, WebSocketSession>> TASK_SESSIONS = new ConcurrentHashMap<>();

    /**
     * 连接建立时回调：从握手路径解析任务号，把该会话登记到对应任务下。
     */
    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        String taskNo = extractTaskNo(session);
        if (taskNo != null) {
            // computeIfAbsent：该任务第一次出现时先创建空表，避免 put 覆盖已有会话
            TASK_SESSIONS.computeIfAbsent(taskNo, k -> new ConcurrentHashMap<>())
                    .put(session.getId(), session);
            log.info("WebSocket连接建立: taskNo={}, sessionId={}", taskNo, session.getId());
        }
    }

    /**
     * 连接关闭时回调：把该会话从任务下移除；若该任务已无任何连接，一并清理外层键，
     * 防止长期运行后内存里堆积废弃的会话。
     */
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

    /**
     * 收到浏览器发来的文本消息时回调。本项目是单向推送（服务端→客户端），
     * 客户端一般不发消息，所以这里只记录日志，不做事。
     */
    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        log.debug("收到WebSocket消息: {}", message.getPayload());
    }

    // 传输出错时回调：记录错误并关闭该连接。
    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.error("WebSocket传输错误: sessionId={}", session.getId(), exception);
        try {
            session.close();
        } catch (Exception e) {
            log.error("关闭WebSocket连接失败", e);
        }
    }

    /**
     * 向关注某任务的所有连接广播一条“进度消息”。
     * message 会被序列化为 JSON 字符串再发送。
     */
    public void sendProgress(String taskNo, Object message) {
        Map<String, WebSocketSession> sessions = TASK_SESSIONS.get(taskNo);
        if (sessions == null || sessions.isEmpty()) {
            // 没有浏览器在关注该任务，直接返回（消息被本次订阅丢弃）
            return;
        }
        try {
            String json = objectMapper.writeValueAsString(message);
            TextMessage textMessage = new TextMessage(json);
            for (WebSocketSession session : sessions.values()) {
                if (session.isOpen()) {
                    // synchronized：同一会话可能被多线程同时推送，加锁避免消息交错/冲突
                    synchronized (session) {
                        session.sendMessage(textMessage);
                    }
                }
            }
        } catch (Exception e) {
            log.error("推送WebSocket消息失败: taskNo={}", taskNo, e);
        }
    }

    /**
     * 向关注某任务的所有连接广播“任务完成”通知。消息体约定为
     * {"type":"COMPLETE","taskNo":"任务号"}，前端据此结束轮询、更新状态。
     */
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

    // 从 WebSocket 握手地址里提取任务号：取路径最后一个“/”之后的部分
    private String extractTaskNo(WebSocketSession session) {
        String path = session.getUri().getPath();
        if (path != null && path.contains("/ws/progress/")) {
            return path.substring(path.lastIndexOf('/') + 1);
        }
        return null;
    }
}
