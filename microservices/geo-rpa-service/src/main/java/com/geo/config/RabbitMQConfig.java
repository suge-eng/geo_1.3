package com.geo.config;

import org.springframework.amqp.core.*;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMQConfig {

    public static final String RPA_TASK_EXCHANGE = "geo.rpa.task.exchange";
    public static final String RPA_TASK_QUEUE = "geo.rpa.task.queue";
    public static final String RPA_TASK_ROUTING_KEY = "geo.rpa.task";
    public static final String RPA_RESULT_EXCHANGE = "geo.rpa.result.exchange";
    public static final String RPA_RESULT_QUEUE = "geo.rpa.result.queue";
    public static final String RPA_RESULT_ROUTING_KEY = "geo.rpa.result";
    public static final String DLX_EXCHANGE = "geo.rpa.dlx.exchange";
    public static final String DLX_QUEUE = "geo.rpa.dlx.queue";
    public static final String DLX_ROUTING_KEY = "geo.rpa.dlx";

    @Bean
    public MessageConverter messageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    @Bean
    public DirectExchange rpaTaskExchange() {
        return new DirectExchange(RPA_TASK_EXCHANGE, true, false);
    }

    @Bean
    public Queue rpaTaskQueue() {
        return QueueBuilder.durable(RPA_TASK_QUEUE)
                .deadLetterExchange(DLX_EXCHANGE)
                .deadLetterRoutingKey(DLX_ROUTING_KEY)
                .ttl(43200000)
                .maxLength(10000)
                .build();
    }

    @Bean
    public Binding rpaTaskBinding() {
        return BindingBuilder.bind(rpaTaskQueue()).to(rpaTaskExchange()).with(RPA_TASK_ROUTING_KEY);
    }

    @Bean
    public DirectExchange rpaResultExchange() {
        return new DirectExchange(RPA_RESULT_EXCHANGE, true, false);
    }

    @Bean
    public Queue rpaResultQueue() {
        return QueueBuilder.durable(RPA_RESULT_QUEUE).build();
    }

    @Bean
    public Binding rpaResultBinding() {
        return BindingBuilder.bind(rpaResultQueue()).to(rpaResultExchange()).with(RPA_RESULT_ROUTING_KEY);
    }

    @Bean
    public DirectExchange dlxExchange() {
        return new DirectExchange(DLX_EXCHANGE, true, false);
    }

    @Bean
    public Queue dlxQueue() {
        return QueueBuilder.durable(DLX_QUEUE).build();
    }

    @Bean
    public Binding dlxBinding() {
        return BindingBuilder.bind(dlxQueue()).to(dlxExchange()).with(DLX_ROUTING_KEY);
    }
}
