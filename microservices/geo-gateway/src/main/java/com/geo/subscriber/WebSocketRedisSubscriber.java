package com.geo.subscriber;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.geo.websocket.ProgressWebSocketHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Map;

@Component
public class WebSocketRedisSubscriber implements MessageListener {

    private static final Logger log = LoggerFactory.getLogger(WebSocketRedisSubscriber.class);

    private static final String CHANNEL_PREFIX = "geo:ws:progress:";

    private final ProgressWebSocketHandler progressWebSocketHandler;
    private final ObjectMapper objectMapper;

    public WebSocketRedisSubscriber(ProgressWebSocketHandler progressWebSocketHandler,
                                    ObjectMapper objectMapper) {
        this.progressWebSocketHandler = progressWebSocketHandler;
        this.objectMapper = objectMapper;
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        String channel = new String(message.getChannel(), StandardCharsets.UTF_8);
        String body = new String(message.getBody(), StandardCharsets.UTF_8);

        log.debug("收到Redis消息: channel={}, body={}", channel, body);

        String taskNo = extractTaskNo(channel);
        if (taskNo == null) {
            log.warn("无法从channel中解析taskNo: channel={}", channel);
            return;
        }

        try {
            Map<String, Object> parsedMap = objectMapper.readValue(body, new TypeReference<Map<String, Object>>() {});
            Object type = parsedMap.get("type");
            if ("COMPLETE".equals(type)) {
                progressWebSocketHandler.sendComplete(taskNo);
            } else {
                progressWebSocketHandler.sendProgress(taskNo, parsedMap);
            }
        } catch (Exception e) {
            log.error("解析Redis消息失败: taskNo={}, body={}", taskNo, body, e);
        }
    }

    private String extractTaskNo(String channel) {
        if (channel != null && channel.startsWith(CHANNEL_PREFIX)) {
            return channel.substring(CHANNEL_PREFIX.length());
        }
        return null;
    }
}
