package com.geo.controller;

import com.geo.config.MinioConfig;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.util.StreamUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.InputStream;
import java.net.URLConnection;

/**
 * 【文件下载代理控制器】
 * 设计思路 —— 为什么用"代理"，而不是让前端/浏览器直接访问 MinIO？
 * 1. 安全：MinIO（对象存储）部署在内网，公网不暴露其真实地址与端口，
 *    避免把 accessKey/secretKey 或内网拓扑暴露给客户端；
 * 2. 统一入口：所有文件访问都收敛到 /api/file/** 这一条路，便于后续在网关层
 *    统一做鉴权、限流、防盗链、访问日志统计；
 * 3. 可替换性：未来若把 MinIO 换成阿里云 OSS / 腾讯云 COS，只需改这里的实现，
 *    前端与业务方无感知。
 *
 * URL 约定：/api/file/{bucket}/{objectName}，其中 bucket 可以省略，省略时用配置里的默认桶。
 */
@RestController
@RequestMapping("/api/file")
public class FileProxyController {

    private static final Logger log = LoggerFactory.getLogger(FileProxyController.class);

    private final MinioClient minioClient;
    private final MinioConfig minioConfig;

    public FileProxyController(MinioClient minioClient, MinioConfig minioConfig) {
        this.minioClient = minioClient;
        this.minioConfig = minioConfig;
    }

    /**
     * 【透传代理 MinIO 对象到浏览器】
     * 设计思路：
     * 1. 用通配路由 @GetMapping("/**") 捕获任意层级的子路径，再手动拆出 bucket/objectName，
     *    这样对象名里可以天然带多级目录（如 screenshots/2026/09/01/xxx.png）；
     * 2. 按文件名猜测 Content-Type（图片/PDF 等），猜不到则回退为二进制流，保证浏览器能正确渲染；
     * 3. 设置强缓存 Cache-Control: public,max-age=86400（1 天）：对象内容不可变，
     *    让浏览器/CDN 缓存以减少重复回源压力；
     * 4. 捕获到"客户端主动断开"（ClientAbortException / Connection reset / Broken pipe）
     *    只打 warn 日志，避免被误当成服务端错误刷爆错误日志。
     */
    @GetMapping("/**")
    public void proxyFile(HttpServletRequest request, HttpServletResponse response) {
        try {
            String requestUri = (String) request.getAttribute(org.springframework.web.servlet.HandlerMapping.PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE);
            if (requestUri == null) {
                requestUri = request.getRequestURI();
            }
            String prefix = "/api/file/";
            int prefixIdx = requestUri.indexOf(prefix);
            String objectPath = prefixIdx >= 0 ? requestUri.substring(prefixIdx + prefix.length()) : requestUri;

            int bucketIdx = objectPath.indexOf('/');
            String bucket;
            String objectName;
            if (bucketIdx > 0) {
                bucket = objectPath.substring(0, bucketIdx);
                objectName = objectPath.substring(bucketIdx + 1);
            } else {
                bucket = minioConfig.getBucket();
                objectName = objectPath;
            }

            if (objectName.isEmpty()) {
                response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                return;
            }

            String contentType = URLConnection.guessContentTypeFromName(objectName);
            if (contentType == null) {
                contentType = MediaType.APPLICATION_OCTET_STREAM_VALUE;
            }
            response.setContentType(contentType);
            response.setHeader(HttpHeaders.CACHE_CONTROL, "public, max-age=86400");

            try (InputStream is = minioClient.getObject(GetObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectName)
                    .build())) {
                StreamUtils.copy(is, response.getOutputStream());
                response.flushBuffer();
            }
        } catch (Exception e) {
            String msg = e.getMessage();
            boolean clientAbort = e instanceof org.apache.catalina.connector.ClientAbortException
                    || (msg != null && (msg.contains("Connection reset") || msg.contains("Broken pipe") || msg.contains("client abort")));
            if (clientAbort) {
                log.warn("客户端断开连接（用户取消/网络中断）: {}", request.getRequestURI());
            } else {
                log.error("文件代理失败: {}", request.getRequestURI(), e);
            }
            if (!response.isCommitted()) {
                response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            }
        }
    }
}
