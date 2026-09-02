package com.geo.config;

import io.minio.MinioClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 【MinIO 客户端 Bean 装配】
 * 设计思路：把 MinioConfig 里读到的连接信息（endpoint + accessKey/secretKey）交给
 * MinioClient.Builder 组装成一个单例 Bean，供 MinioService / FileProxyController
 * 等地方注入复用——避免每个调用点都重复 new 客户端、重复建立连接资源。
 */
@Configuration
public class MinioClientConfig {

    private final MinioConfig minioConfig;

    public MinioClientConfig(MinioConfig minioConfig) {
        this.minioConfig = minioConfig;
    }

    /**
     * 用内网 endpoint + 访问凭证构建 MinioClient；
     * 使用内网地址是为了避免服务到 MinIO 之间的流量绕公网，更快也更安全。
     */
    @Bean
    public MinioClient minioClient() {
        return MinioClient.builder()
                .endpoint(minioConfig.getEndpoint())
                .credentials(minioConfig.getAccessKey(), minioConfig.getSecretKey())
                .build();
    }
}
