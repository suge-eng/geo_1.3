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

/**
 * 文件存储服务，封装对 MinIO 对象存储的读写操作。
 *
 * 设计思路：
 *   - 本服务统一负责 RPA 执行过程中产生的截图等文件的落盘与访问 URL 生成，
 *     上传与删除都收敛到这一个组件，业务层（controller / 回调）不直接接触 MinIO SDK。
 *   - 文件名用「日期目录 + UUID」生成，天然避免同名覆盖，也便于按天归档清理。
 *   - 桶在服务启动时自动创建并设成公开只读，前端拿到 URL 即可直接展示截图；
 *     对外返回的是网关转发路径（/api/file/...），而非 MinIO 内网地址，解耦具体存储位置。
 *   - 删除失败仅记录日志不抛异常：截图是次要数据，删除失败不应影响主流程。
 */
@Service
public class MinioService {

    private static final Logger log = LoggerFactory.getLogger(MinioService.class);

    private final MinioClient minioClient;
    private final MinioConfig minioConfig;

    public MinioService(MinioClient minioClient, MinioConfig minioConfig) {
        this.minioClient = minioClient;
        this.minioConfig = minioConfig;
        // 依赖注入完成后立刻初始化存储桶，保证首次上传前桶就绪；失败仅记录日志，不阻塞服务启动。
        ensureBucketExists();
    }

    /**
     * 启动期自检：桶不存在则创建，并配置桶级访问策略。整个过程捕获异常，避免因 MinIO 未就绪导致应用启动失败。
     */
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

    /**
     * 将桶设为公开只读（允许匿名读取对象）。
     * 采用 S3 兼容策略 JSON：只开放 GetObject/ListBucket 的读权限，禁止写操作，
     * 用于让前端/浏览器无需鉴权即可加载截图。
     */
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

    /**
     * 上传文件并返回可对外访问的 URL。
     *
     * @param file 前端/脚本上传的文件（此处为截图）
     * @return 网关转发路径形式的外部 URL（/api/file/{bucket}/{filename}）
     */
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

    /**
     * 删除文件。入参是外部 URL，内部反解出对象名后调用 MinIO 删除；
     * 删除失败仅记日志，不向上抛出，避免因清理失败中断调用方逻辑。
     */
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

    /**
     * 生成唯一对象名，格式为 screenshots/yyyy/MM/dd/{uuid}{ext}。
     * 按日期分目录既方便归档，又避免单一目录下对象过多影响访问性能；
     * UUID 去连字符后拼接原扩展名，保证文件名唯一且类型信息不丢失。
     */
    private String generateFilename(String originalFilename) {
        String extension = "";
        if (originalFilename != null && originalFilename.contains(".")) {
            extension = originalFilename.substring(originalFilename.lastIndexOf("."));
        }
        String datePath = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy/MM/dd"));
        String uuid = UUID.randomUUID().toString().replace("-", "");
        return "screenshots/" + datePath + "/" + uuid + extension;
    }

    /**
     * 由对象名拼出外部访问 URL。返回网关路径而非 MinIO 真实地址，
     * 这样前端只依赖网关，MinIO 换地址/换部署都不影响使用。
     */
    private String buildPublicUrl(String filename) {
        return "/api/file/" + minioConfig.getBucket() + "/" + filename;
    }

    /**
     * 从外部 URL 反解对象名：定位到桶名后截取其后的部分作为 Object Key，
     * 供删除接口使用。URL 中找不到桶名时原样返回，做兜底处理。
     */
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
