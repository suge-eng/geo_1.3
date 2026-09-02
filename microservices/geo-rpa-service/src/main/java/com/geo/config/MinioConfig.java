package com.geo.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * MinIO 连接配置载体：通过 @ConfigurationProperties(prefix = "minio") 把配置文件里的
 * minio.* 项自动绑定到本类字段，供 {@link MinioClientConfig} 构造客户端、{@link MinioService}
 * 确定桶名使用。集中放配置，避免连接信息散落在各业务代码里。
 */
@Configuration
@ConfigurationProperties(prefix = "minio")
public class MinioConfig {

    /** MinIO 服务端地址（内网直连用）。 */
    private String endpoint;
    /** 对外可访问的地址（预留，用于生成外部下载链接的场景）。 */
    private String publicEndpoint;
    /** 访问密钥 AccessKey。 */
    private String accessKey;
    /** 访问密钥 SecretKey。 */
    private String secretKey;
    /** 默认存储桶名称。 */
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
