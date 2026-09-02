package com.geo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 【文件服务启动类 - MinIO对象存储接入】
 * 设计思路：
 * 1. 这是geo-file-service微服务的入口，专门负责"截图/附件"等二进制文件的存取
 * 2. 职责：
 *    - 封装MinIO（开源对象存储，兼容AWS S3协议）SDK调用
 *    - 对外提供统一的文件代理访问接口（/api/file/**）
 *    - 自动建Bucket、设公开读策略（应用启动时自检，无需运维手动配置）
 * 3. 为什么用独立服务而不是直接前端连MinIO？
 *    - 屏蔽MinIO真实地址，增强安全（公网只暴露网关/file-service）
 *    - 统一入口后可加鉴权、限流、访问日志、防盗链等
 *    - 未来可无缝替换MinIO→阿里云OSS/腾讯云COS（业务代码无感知）
 */
@SpringBootApplication
public class GeoFileServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(GeoFileServiceApplication.class, args);
    }
}