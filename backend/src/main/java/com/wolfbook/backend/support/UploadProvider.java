package com.wolfbook.backend.support;

import org.springframework.web.multipart.MultipartFile;

public interface UploadProvider {

    default String upload(MultipartFile file) {
        return uploadObject(file, "").url();
    }

    default String upload(MultipartFile file, String pathPrefix) {
        return uploadObject(file, pathPrefix).url();
    }

    UploadResult uploadObject(MultipartFile file, String pathPrefix);

    UploadResult uploadObject(String originalFilename, String contentType, byte[] content, String pathPrefix);

    record UploadResult(String key, String url) {
    }
}
