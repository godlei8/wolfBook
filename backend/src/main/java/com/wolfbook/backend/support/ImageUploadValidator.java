package com.wolfbook.backend.support;

import com.wolfbook.backend.common.ApiException;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;

/**
 * 上传前校验：只允许 jpg / png / gif / webp 图片，且按文件头而不是客户端声明的类型判断。
 */
@Component
public class ImageUploadValidator {

    public static final long MAX_IMAGE_BYTES = 5L * 1024 * 1024;

    private static final Set<String> ALLOWED_EXTENSIONS = Set.of("jpg", "jpeg", "png", "gif", "webp");

    public void validate(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ApiException(4000, "上传文件不能为空");
        }
        if (file.getSize() > MAX_IMAGE_BYTES) {
            throw new ApiException(4000, "图片不能超过 5MB");
        }
        String filename = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
        int dot = filename.lastIndexOf('.');
        String extension = dot < 0 ? "" : filename.substring(dot + 1).toLowerCase(Locale.ROOT);
        if (!ALLOWED_EXTENSIONS.contains(extension)) {
            throw new ApiException(4000, "仅支持 jpg / png / gif / webp 图片");
        }
        if (!looksLikeImage(file)) {
            throw new ApiException(4000, "文件内容不是有效图片");
        }
    }

    private boolean looksLikeImage(MultipartFile file) {
        byte[] head = new byte[12];
        int read;
        try (InputStream in = file.getInputStream()) {
            read = in.readNBytes(head, 0, head.length);
        } catch (IOException exception) {
            return false;
        }
        if (read < 4) {
            return false;
        }
        // JPEG: FF D8 FF
        if ((head[0] & 0xFF) == 0xFF && (head[1] & 0xFF) == 0xD8 && (head[2] & 0xFF) == 0xFF) {
            return true;
        }
        // PNG: 89 50 4E 47 0D 0A 1A 0A
        if (read >= 8 && (head[0] & 0xFF) == 0x89 && head[1] == 'P' && head[2] == 'N' && head[3] == 'G'
                && head[4] == '\r' && head[5] == '\n' && head[6] == 0x1A && head[7] == '\n') {
            return true;
        }
        // GIF: "GIF87a" / "GIF89a"
        if (read >= 6 && head[0] == 'G' && head[1] == 'I' && head[2] == 'F' && head[3] == '8'
                && (head[4] == '7' || head[4] == '9') && head[5] == 'a') {
            return true;
        }
        // WEBP: "RIFF" .... "WEBP"
        return read >= 12 && Arrays.equals(head, 0, 4, "RIFF".getBytes(), 0, 4)
                && Arrays.equals(head, 8, 12, "WEBP".getBytes(), 0, 4);
    }
}
