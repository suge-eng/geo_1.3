package com.geo.service;

import com.geo.config.MinioConfig;
import com.geo.common.BusinessException;
import com.geo.common.ResultCode;
import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.SetBucketPolicyArgs;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

@Service
public class MinioService {

    private static final Logger log = LoggerFactory.getLogger(MinioService.class);

    private final MinioClient minioClient;
    private final MinioConfig minioConfig;

    public MinioService(MinioClient minioClient, MinioConfig minioConfig) {
        this.minioClient = minioClient;
        this.minioConfig = minioConfig;
        ensureBucketExists();
    }

    private void ensureBucketExists() {
        try {
            boolean exists = minioClient.bucketExists(BucketExistsArgs.builder().bucket(minioConfig.getBucket()).build());
            if (!exists) {
                minioClient.makeBucket(MakeBucketArgs.builder().bucket(minioConfig.getBucket()).build());
                log.info("创建 MinIO Bucket: {}", minioConfig.getBucket());
            }
            setBucketPublicPolicy();
        } catch (Exception e) {
            log.error("创建 Bucket 失败", e);
        }
    }

    private void setBucketPublicPolicy() {
        try {
            String policy = "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\",\"Principal\":{\"AWS\":[\"*\"]},\"Action\":[\"s3:GetBucketLocation\",\"s3:ListBucket\"],\"Resource\":[\"arn:aws:s3:::" + minioConfig.getBucket() + "\"]},{\"Effect\":\"Allow\",\"Principal\":{\"AWS\":[\"*\"]},\"Action\":[\"s3:GetObject\"],\"Resource\":[\"arn:aws:s3:::" + minioConfig.getBucket() + "/*\"]}]}";
            minioClient.setBucketPolicy(SetBucketPolicyArgs.builder()
                    .bucket(minioConfig.getBucket())
                    .config(policy)
                    .build());
            log.info("已设置 Bucket {} 为公开只读访问", minioConfig.getBucket());
        } catch (Exception e) {
            log.error("设置 Bucket 策略失败", e);
        }
    }

    public String uploadFile(MultipartFile file) {
        String filename = generateFilename(file.getOriginalFilename());
        try (InputStream is = file.getInputStream()) {
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(minioConfig.getBucket())
                    .object(filename)
                    .stream(is, file.getSize(), -1)
                    .contentType(file.getContentType())
                    .build());
            log.info("文件上传成功, filename={}, publicUrl={}", filename, buildPublicUrl(filename));
            return buildPublicUrl(filename);
        } catch (Exception e) {
            log.error("上传文件失败", e);
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "文件上传失败");
        }
    }

    public void deleteFile(String url) {
        String filename = extractFilename(url);
        try {
            minioClient.removeObject(RemoveObjectArgs.builder()
                    .bucket(minioConfig.getBucket())
                    .object(filename)
                    .build());
        } catch (Exception e) {
            log.error("删除文件失败: {}", url, e);
        }
    }

    private String generateFilename(String originalFilename) {
        String extension = "";
        if (originalFilename != null && originalFilename.contains(".")) {
            extension = originalFilename.substring(originalFilename.lastIndexOf("."));
        }
        String datePath = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy/MM/dd"));
        String uuid = UUID.randomUUID().toString().replace("-", "");
        return "screenshots/" + datePath + "/" + uuid + extension;
    }

    private String buildPublicUrl(String filename) {
        return "/api/file/" + minioConfig.getBucket() + "/" + filename;
    }

    private String extractFilename(String url) {
        if (url == null || url.isEmpty()) {
            return "";
        }
        String bucket = minioConfig.getBucket();
        int idx = url.indexOf(bucket);
        if (idx >= 0) {
            return url.substring(idx + bucket.length() + 1);
        }
        return url;
    }
}
