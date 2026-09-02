package com.geo.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 【MinIO配置属性类 - 配置绑定】
 * 设计思路：
 * 1. 使用Spring Boot的@ConfigurationProperties，把application.yml中minio.*开头的配置自动注入到字段
 * 2. endpoint vs publicEndpoint的区别：
 *    - endpoint：内网访问地址（服务间调用用，通常走k8s service名或内网IP）
 *    - publicEndpoint：公网访问地址（用户浏览器访问用，可选，当前主要用网关代理，不直接暴露）
 * 3. accessKey/secretKey：凭证，不要硬编码在代码里，生产环境建议用配置中心或K8s Secret注入
 */
@Configuration
@ConfigurationProperties(prefix = "minio")
public class MinioConfig {

    private String endpoint;
    private String publicEndpoint;
    private String accessKey;
    private String secretKey;
    private String bucket;

    public String getEndpoint() {
        return endpoint;
    }

    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint;
    }

    public String getPublicEndpoint() {
        return publicEndpoint;
    }

    public void setPublicEndpoint(String publicEndpoint) {
        this.publicEndpoint = publicEndpoint;
    }

    public String getAccessKey() {
        return accessKey;
    }

    public void setAccessKey(String accessKey) {
        this.accessKey = accessKey;
    }

    public String getSecretKey() {
        return secretKey;
    }

    public void setSecretKey(String secretKey) {
        this.secretKey = secretKey;
    }

    public String getBucket() {
        return bucket;
    }

    public void setBucket(String bucket) {
        this.bucket = bucket;
    }
}