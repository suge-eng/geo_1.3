package com.geo.config;

import io.minio.MinioClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 装配 MinIO 客户端 Bean：从 {@link MinioConfig} 读取连接串与密钥，构造一个真正的 MinioClient
 * 注入到 {@link MinioService} 使用。连接信息集中在配置文件，此处只做「读配置 -> 建客户端」这一步。
 */
@Configuration
public class MinioClientConfig {

    private final MinioConfig minioConfig;

    public MinioClientConfig(MinioConfig minioConfig) {
        this.minioConfig = minioConfig;
    }

    @Bean
    public MinioClient minioClient() {
        return MinioClient.builder()
                .endpoint(minioConfig.getEndpoint())
                .credentials(minioConfig.getAccessKey(), minioConfig.getSecretKey())
                .build();
    }
}
