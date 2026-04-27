package com.wolfbook.backend.ai.service;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Component
public class AiDocumentTextExtractor {

    public String extract(MultipartFile file) {
        String filename = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase();
        try {
            byte[] bytes = file.getBytes();
            if (filename.endsWith(".pdf")) {
                try (PDDocument document = Loader.loadPDF(bytes)) {
                    return new PDFTextStripper().getText(document);
                }
            }
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalArgumentException("文档解析失败：" + exception.getMessage(), exception);
        }
    }
}
