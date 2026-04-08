package com.wolfbook.backend.support;

import com.wolfbook.backend.common.ApiException;
import com.wolfbook.backend.config.UploadProperties;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

@Component
public class LocalUploadProvider implements UploadProvider {

    private final UploadProperties uploadProperties;

    public LocalUploadProvider(UploadProperties uploadProperties) {
        this.uploadProperties = uploadProperties;
    }

    @Override
    public String upload(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ApiException(4000, "上传文件不能为空");
        }
        try {
            Path dir = Path.of(uploadProperties.getDir()).toAbsolutePath().normalize();
            Files.createDirectories(dir);
            String original = file.getOriginalFilename() == null ? "image.png" : file.getOriginalFilename();
            String extension = original.contains(".") ? original.substring(original.lastIndexOf('.')) : ".png";
            String filename = UUID.randomUUID().toString().replace("-", "") + extension;
            Files.copy(file.getInputStream(), dir.resolve(filename), StandardCopyOption.REPLACE_EXISTING);
            return "/uploads/" + filename;
        } catch (IOException exception) {
            throw new ApiException(5001, "图片上传失败");
        }
    }
}
