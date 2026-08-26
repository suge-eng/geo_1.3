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
