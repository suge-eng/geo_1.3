package com.geo.config;

import com.geo.subscriber.WebSocketRedisSubscriber;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.listener.adapter.MessageListenerAdapter;

/**
 * Redis 发布/订阅（Pub/Sub）配置。
 *
 * 设计思路：各微服务之间需要“任务进度”实时互通，但它们彼此隔离、没有直接连接。
 * 于是借用 Redis 的发布订阅机制作为消息总线——生产任务的下游服务把进度消息发布到
 * Redis 的某个频道（geo:ws:progress:任务号），网关这里订阅后，再经 WebSocket 推给浏览器。
 *
 * 两个 Bean 的分工：
 *   - RedisMessageListenerContainer：真正的“订阅器”，持续监听频道并接收消息；
 *   - MessageListenerAdapter：把收到的消息转交给 WebSocketRedisSubscriber.onMessage 处理。
 */
@Configuration
public class RedisPubSubConfig {

    @Bean
    public RedisMessageListenerContainer redisMessageListenerContainer(
            RedisConnectionFactory connectionFactory,
            MessageListenerAdapter listenerAdapter) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        // 用通配符订阅：geo:ws:progress: 开头的所有频道（: 后面跟的是任务号）
        container.addMessageListener(listenerAdapter, new PatternTopic("geo:ws:progress:*"));
        return container;
    }

    @Bean
    public MessageListenerAdapter messageListenerAdapter(WebSocketRedisSubscriber subscriber) {
        // 通过反射调用 subscriber 的 onMessage 方法：Redis 一收到消息就回调它
        return new MessageListenerAdapter(subscriber, "onMessage");
    }
}
