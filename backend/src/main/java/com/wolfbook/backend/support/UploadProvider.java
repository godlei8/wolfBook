package com.wolfbook.backend.support;

import org.springframework.web.multipart.MultipartFile;

public interface UploadProvider {

    String upload(MultipartFile file);
}
