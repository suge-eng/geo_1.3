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
 * 【MinIO 对象存储封装服务】
 * 设计思路：
 * 1. 把 MinIO SDK 的底层调用（putObject / getObject / removeObject / setBucketPolicy）
 *    封装成业务友好的方法，让调用方只关心"上传一个文件 → 拿到可访问的 URL"，
 *    完全不用感知 MinIO 的连接与对象模型；
 * 2. 文件名生成策略：按日期建目录 + UUID 去重，既方便按天归档/清理，又避免重名互相覆盖；
 * 3. 构造函数里自动 ensureBucketExists()：应用启动即自检桶是否存在、是否已设好公开读策略，
 *    免去运维手动初始化——因为文件本身走代理对外公开访问，统一设为"公开只读"。
 */
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

    /**
     * 幂等初始化：只有桶不存在时才创建，随后统一刷新公开读策略，保证服务可反复重启。
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
     * 把桶策略设为"公开只读"：允许匿名拉取对象（GetObject）、列桶（ListBucket），
     * 但不允许上传/删除——这样浏览器/网关可直接读取，而写入仍只能走本服务。
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
     * 上传 MultipartFile 到默认桶，成功后返回可访问的相对公网 URL（走文件代理入口）。
     * 失败统一抛出 BusinessException，由全局异常处理器兜底返回给前端。
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
     * 按 URL 删除对象：先从完整 URL 里逆推出对象名，再调用 removeObject 删除。
     * 删除失败只记日志不抛异常——删除通常是"尽力而为"的清理动作，不应阻断主流程。
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
     * 生成对象名：目录按 yyyy/MM/dd 分包，文件名用 UUID（去掉横杠）+ 原扩展名。
     * 设计意义：按天归档便于清理过期文件；UUID 保证唯一避免重名覆盖。
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
     * 构造对外访问地址：统一返回走本服务代理的相对路径 /api/file/{bucket}/{object}，
     * 而不是 MinIO 的内网直连地址——这样前端拿到的永远是安全、可替换的代理 URL。
     */
    private String buildPublicUrl(String filename) {
        return "/api/file/" + minioConfig.getBucket() + "/" + filename;
    }

    /**
     * 从 URL 里抽出对象名：定位到桶名之后的部分即为对象名；
     * 若 URL 里不含桶名（异常情况），则降级把整个 URL 当作对象名返回。
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
