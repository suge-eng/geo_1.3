package com.geo.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateTimeDeserializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateTimeSerializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 【Jackson序列化配置】
 *
 * 设计思路：
 * Spring Boot默认用Jackson做JSON序列化/反序列化。
 * 默认配置下LocalDateTime会被转成数组格式[2024,1,1,12,0]，前端很难处理。
 * 所以我们自定义一个统一格式："yyyy-MM-dd HH:mm:ss"。
 *
 * 两个关键的兼容性开关：
 * 1. WRITE_DATES_AS_TIMESTAMPS=false：不要把日期转成时间戳/数组，用字符串
 * 2. FAIL_ON_UNKNOWN_PROPERTIES=false：前端传了后端不认识的字段时，不要报错（前后端版本不一致时很有用）
 */
@Configuration
public class JacksonConfig {

    /** 统一的日期时间格式 */
    private static final String DATE_TIME_PATTERN = "yyyy-MM-dd HH:mm:ss";

    /**
     * 全局ObjectMapper配置。@Primary保证所有自动注入的地方都用这一个。
     */
    @Bean
    @Primary
    public ObjectMapper objectMapper() {
        ObjectMapper objectMapper = new ObjectMapper();

        // 注册Java 8时间类型模块（LocalDateTime等）
        JavaTimeModule javaTimeModule = new JavaTimeModule();
        DateTimeFormatter dateTimeFormatter = DateTimeFormatter.ofPattern(DATE_TIME_PATTERN);
        javaTimeModule.addSerializer(LocalDateTime.class, new LocalDateTimeSerializer(dateTimeFormatter));
        javaTimeModule.addDeserializer(LocalDateTime.class, new LocalDateTimeDeserializer(dateTimeFormatter));
        objectMapper.registerModule(javaTimeModule);

        // 禁用"日期转时间戳"，强制用字符串格式
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        // 禁用"未知字段报错"，前后端版本不一致时不会因为多一个字段就崩
        objectMapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

        return objectMapper;
    }
}