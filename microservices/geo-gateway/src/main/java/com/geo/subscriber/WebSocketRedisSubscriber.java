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

/**
 * Redis 消息 → WebSocket 推送的“桥接器”。
 *
 * 它扮演整个实时进度链路里的中转站：
 *   下游微服务  --发布进度-->  Redis 频道(geo:ws:progress:任务号)  --订阅-->  本类  --转发-->  WebSocket
 *
 * 收到一条消息后做的事：
 *   1. 从频道名里解析出“任务号”（taskNo）；
 *   2. 把消息体（JSON）解析成 Map；
 *   3. 若 type 为 COMPLETE 表示任务完成，调用 sendComplete；
 *      否则表示普通进度更新，把整段数据调用 sendProgress 原样推给前端。
 */
@Component
public class WebSocketRedisSubscriber implements MessageListener {

    private static final Logger log = LoggerFactory.getLogger(WebSocketRedisSubscriber.class);

    // 频道名的固定前缀，后面的后缀即为任务号
    private static final String CHANNEL_PREFIX = "geo:ws:progress:";

    // 进度推送的最终执行者；ObjectMapper 用于把 JSON 字符串解析成 Map
    private final ProgressWebSocketHandler progressWebSocketHandler;
    private final ObjectMapper objectMapper;

    public WebSocketRedisSubscriber(ProgressWebSocketHandler progressWebSocketHandler,
                                    ObjectMapper objectMapper) {
        this.progressWebSocketHandler = progressWebSocketHandler;
        this.objectMapper = objectMapper;
    }

    /**
     * Redis 订阅回调：每当订阅的频道里出现新消息，Redis 容器就会调用本方法。
     *
     * @param message Redis 原始消息（channel 为频道名，body 为消息体字节）
     * @param pattern 匹配到的订阅模式（这里用通配订阅，故传回匹配的具体模式）
     */
    @Override
    public void onMessage(Message message, byte[] pattern) {
        // 把频道名、消息体从字节解码成可读字符串
        String channel = new String(message.getChannel(), StandardCharsets.UTF_8);
        String body = new String(message.getBody(), StandardCharsets.UTF_8);

        log.debug("收到Redis消息: channel={}, body={}", channel, body);

        // 从频道名中提取任务号；取不到说明频道格式异常，直接丢弃
        String taskNo = extractTaskNo(channel);
        if (taskNo == null) {
            log.warn("无法从channel中解析taskNo: channel={}", channel);
            return;
        }

        try {
            // 消息体约定为 JSON，解析成通用 Map 便于读取 type 等字段
            Map<String, Object> parsedMap = objectMapper.readValue(body, new TypeReference<Map<String, Object>>() {});
            Object type = parsedMap.get("type");
            if ("COMPLETE".equals(type)) {
                // 完成通知：只推送一个轻量的“完成”信号
                progressWebSocketHandler.sendComplete(taskNo);
            } else {
                // 普通进度：把解析后的整份数据推给关注该任务的所有连接
                progressWebSocketHandler.sendProgress(taskNo, parsedMap);
            }
        } catch (Exception e) {
            log.error("解析Redis消息失败: taskNo={}, body={}", taskNo, body, e);
        }
    }

    // 去掉频道名前缀，剩下的就是任务号
    private String extractTaskNo(String channel) {
        if (channel != null && channel.startsWith(CHANNEL_PREFIX)) {
            return channel.substring(CHANNEL_PREFIX.length());
        }
        return null;
    }
}
