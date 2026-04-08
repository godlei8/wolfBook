package com.wolfbook.backend.support;

import com.wolfbook.backend.common.ApiException;
import com.wolfbook.backend.config.UploadProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.UUID;

@Component
@ConditionalOnProperty(prefix = "wolfbook.upload", name = "provider", havingValue = "local", matchIfMissing = true)
public class LocalUploadProvider implements UploadProvider {

    private final UploadProperties uploadProperties;

    public LocalUploadProvider(UploadProperties uploadProperties) {
        this.uploadProperties = uploadProperties;
    }

    @Override
    public UploadResult uploadObject(MultipartFile file, String pathPrefix) {
        if (file == null || file.isEmpty()) {
            throw new ApiException(4000, "上传文件不能为空");
        }
        try {
            return uploadObject(
                    file.getOriginalFilename() == null ? "image.png" : file.getOriginalFilename(),
                    file.getContentType(),
                    file.getBytes(),
                    pathPrefix
            );
        } catch (IOException exception) {
            throw new ApiException(5001, "图片上传失败");
        }
    }

    @Override
    public UploadResult uploadObject(String originalFilename, String contentType, byte[] content, String pathPrefix) {
        if (content == null || content.length == 0) {
            throw new ApiException(4000, "上传文件不能为空");
        }
        try {
            Path dir = resolveDirectory(pathPrefix);
            Files.createDirectories(dir);
            String original = sanitizeFilename(originalFilename);
            String extension = original.contains(".") ? original.substring(original.lastIndexOf('.')) : ".png";
            String filename = UUID.randomUUID().toString().replace("-", "") + extension;
            Path target = dir.resolve(filename);
            Files.copy(new ByteArrayInputStream(content), target, StandardCopyOption.REPLACE_EXISTING);
            String publicPath = buildPublicPath(pathPrefix, filename);
            return new UploadResult(filename, publicPath);
        } catch (IOException exception) {
            throw new ApiException(5001, "图片上传失败");
        }
    }

    private Path resolveDirectory(String pathPrefix) {
        Path base = Path.of(uploadProperties.getDir()).toAbsolutePath().normalize();
        if (pathPrefix == null || pathPrefix.isBlank()) {
            return base;
        }
        return base.resolve(normalizePrefix(pathPrefix)).normalize();
    }

    private String buildPublicPath(String pathPrefix, String filename) {
        String normalizedPrefix = normalizePrefix(pathPrefix);
        if (normalizedPrefix.isBlank()) {
            return "/uploads/" + filename;
        }
        return "/uploads/" + normalizedPrefix + "/" + filename;
    }

    private String normalizePrefix(String pathPrefix) {
        return pathPrefix == null ? "" : pathPrefix.replace("\\", "/").replaceAll("^/+", "").replaceAll("/+$", "");
    }

    private String sanitizeFilename(String originalFilename) {
        String normalized = originalFilename == null ? "file.bin" : originalFilename.replace("\\", "/");
        int slash = normalized.lastIndexOf('/');
        String fileName = slash >= 0 ? normalized.substring(slash + 1) : normalized;
        return fileName.isBlank() ? "file.bin" : fileName.toLowerCase(Locale.ROOT);
    }
}
