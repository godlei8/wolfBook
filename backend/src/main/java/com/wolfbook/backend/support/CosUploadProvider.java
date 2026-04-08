package com.wolfbook.backend.support;

import com.qcloud.cos.COSClient;
import com.qcloud.cos.model.ObjectMetadata;
import com.qcloud.cos.model.PutObjectRequest;
import com.wolfbook.backend.common.ApiException;
import com.wolfbook.backend.config.UploadProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.util.Locale;
import java.util.UUID;

@Component
@ConditionalOnProperty(prefix = "wolfbook.upload", name = "provider", havingValue = "cos")
public class CosUploadProvider implements UploadProvider {

    private final COSClient cosClient;
    private final UploadProperties uploadProperties;

    public CosUploadProvider(COSClient cosClient, UploadProperties uploadProperties) {
        this.cosClient = cosClient;
        this.uploadProperties = uploadProperties;
    }

    @Override
    public UploadResult uploadObject(MultipartFile file, String pathPrefix) {
        if (file == null || file.isEmpty()) {
            throw new ApiException(4000, "上传文件不能为空");
        }
        try {
            return uploadObject(
                    file.getOriginalFilename() == null ? "file.bin" : file.getOriginalFilename(),
                    file.getContentType(),
                    file.getBytes(),
                    pathPrefix
            );
        } catch (IOException exception) {
            throw new ApiException(5001, "文件上传到 COS 失败");
        }
    }

    @Override
    public UploadResult uploadObject(String originalFilename, String contentType, byte[] content, String pathPrefix) {
        if (content == null || content.length == 0) {
            throw new ApiException(4000, "上传文件不能为空");
        }
        UploadProperties.CosProperties cos = uploadProperties.getCos();
        if (isBlank(cos.getBucket()) || isBlank(cos.getRegion()) || isBlank(cos.getSecretId()) || isBlank(cos.getSecretKey())) {
            throw new ApiException(5001, "COS 配置不完整，请补充 bucket、region、secretId 和 secretKey");
        }

        String key = buildObjectKey(originalFilename, pathPrefix);
        ObjectMetadata metadata = new ObjectMetadata();
        metadata.setContentLength(content.length);
        if (!isBlank(contentType)) {
            metadata.setContentType(contentType);
        }

        try (ByteArrayInputStream inputStream = new ByteArrayInputStream(content)) {
            PutObjectRequest request = new PutObjectRequest(cos.getBucket(), key, inputStream, metadata);
            cosClient.putObject(request);
            return new UploadResult(key, resolveUrl(cos.getBucket(), key));
        } catch (IOException exception) {
            throw new ApiException(5001, "文件上传到 COS 失败");
        } catch (Exception exception) {
            throw new ApiException(5001, "文件上传到 COS 失败: " + exception.getMessage());
        }
    }

    private String buildObjectKey(String originalFilename, String pathPrefix) {
        String extension = extractExtension(originalFilename);
        String normalizedPrefix = normalizePrefix(pathPrefix);
        String basePrefix = normalizePrefix(uploadProperties.getCos().getPrefix());
        LocalDate today = LocalDate.now();
        String datePath = today.getYear() + "/" + String.format("%02d", today.getMonthValue()) + "/" + String.format("%02d", today.getDayOfMonth());
        String fileName = UUID.randomUUID().toString().replace("-", "") + extension;
        StringBuilder builder = new StringBuilder();
        if (!basePrefix.isBlank()) {
            builder.append(basePrefix).append('/');
        }
        if (!normalizedPrefix.isBlank()) {
            builder.append(normalizedPrefix).append('/');
        }
        builder.append(datePath).append('/').append(fileName);
        return builder.toString();
    }

    private String resolveUrl(String bucket, String key) {
        String publicBaseUrl = uploadProperties.getCos().getPublicBaseUrl();
        if (!isBlank(publicBaseUrl)) {
            return trimTrailingSlash(publicBaseUrl) + "/" + key;
        }
        return cosClient.getObjectUrl(bucket, key).toString();
    }

    private String extractExtension(String originalFilename) {
        String sanitized = sanitizeFilename(originalFilename);
        int dotIndex = sanitized.lastIndexOf('.');
        if (dotIndex < 0) {
            return ".bin";
        }
        return sanitized.substring(dotIndex).toLowerCase(Locale.ROOT);
    }

    private String sanitizeFilename(String originalFilename) {
        String normalized = originalFilename == null ? "file.bin" : originalFilename.replace("\\", "/");
        int slashIndex = normalized.lastIndexOf('/');
        String fileName = slashIndex >= 0 ? normalized.substring(slashIndex + 1) : normalized;
        return fileName.isBlank() ? "file.bin" : fileName;
    }

    private String normalizePrefix(String pathPrefix) {
        return pathPrefix == null ? "" : pathPrefix.replace("\\", "/").replaceAll("^/+", "").replaceAll("/+$", "");
    }

    private String trimTrailingSlash(String value) {
        return value.replaceAll("/+$", "");
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
