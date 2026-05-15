package com.wolfbook.backend.service.assistant;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wolfbook.backend.common.ApiException;
import com.wolfbook.backend.dto.AssistantDtos;
import com.wolfbook.backend.entity.AssistantDocumentEntity;
import com.wolfbook.backend.entity.AssistantPublishVersionEntity;
import com.wolfbook.backend.mapper.AssistantDocumentMapper;
import com.wolfbook.backend.mapper.AssistantPublishVersionMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
public class AssistantPublishService {

    private static final TypeReference<List<Integer>> INTEGER_LIST = new TypeReference<>() {
    };

    private final AssistantDocumentMapper assistantDocumentMapper;
    private final AssistantPublishVersionMapper assistantPublishVersionMapper;
    private final ObjectMapper objectMapper;

    public AssistantPublishService(
            AssistantDocumentMapper assistantDocumentMapper,
            AssistantPublishVersionMapper assistantPublishVersionMapper,
            ObjectMapper objectMapper
    ) {
        this.assistantDocumentMapper = assistantDocumentMapper;
        this.assistantPublishVersionMapper = assistantPublishVersionMapper;
        this.objectMapper = objectMapper;
    }

    public Integer getCurrentVersionId() {
        AssistantPublishVersionEntity current = assistantPublishVersionMapper.selectOne(
                new LambdaQueryWrapper<AssistantPublishVersionEntity>()
                        .eq(AssistantPublishVersionEntity::getIsCurrent, 1)
                        .orderByDesc(AssistantPublishVersionEntity::getId)
                        .last("LIMIT 1")
        );
        return current == null ? null : current.getId();
    }

    public List<AssistantDtos.AdminPublishVersionView> listVersions() {
        return assistantPublishVersionMapper.selectList(new LambdaQueryWrapper<AssistantPublishVersionEntity>().orderByDesc(AssistantPublishVersionEntity::getId))
                .stream()
                .map(this::toView)
                .toList();
    }

    public AssistantDtos.AdminPublishVersionView publish(String adminUsername, String notes) {
        List<AssistantDocumentEntity> documents = assistantDocumentMapper.selectList(
                new LambdaQueryWrapper<AssistantDocumentEntity>()
                        .eq(AssistantDocumentEntity::getReviewStatus, AssistantConstants.REVIEW_APPROVED)
                        .eq(AssistantDocumentEntity::getProcessingStatus, AssistantConstants.STATUS_READY)
                        .orderByAsc(AssistantDocumentEntity::getId)
        );
        if (documents.isEmpty()) {
            throw new ApiException(4000, "No approved assistant documents to publish");
        }

        assistantPublishVersionMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<AssistantPublishVersionEntity>()
                .set(AssistantPublishVersionEntity::getIsCurrent, 0)
                .eq(AssistantPublishVersionEntity::getIsCurrent, 1));

        AssistantPublishVersionEntity entity = new AssistantPublishVersionEntity();
        entity.setVersionName("assistant-v" + System.currentTimeMillis());
        entity.setNotes(notes);
        entity.setDocumentIds(writeDocumentIds(documents.stream().map(AssistantDocumentEntity::getId).toList()));
        entity.setIsCurrent(1);
        entity.setPublishedBy(adminUsername);
        entity.setCreateTime(LocalDateTime.now());
        assistantPublishVersionMapper.insert(entity);

        for (AssistantDocumentEntity document : documents) {
            document.setPublishVersionId(entity.getId());
            assistantDocumentMapper.updateById(document);
        }
        return toView(entity);
    }

    public AssistantDtos.AdminPublishVersionView rollback(Integer versionId) {
        AssistantPublishVersionEntity target = assistantPublishVersionMapper.selectById(versionId);
        if (target == null) {
            throw new ApiException(4004, "Publish version not found");
        }
        assistantPublishVersionMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<AssistantPublishVersionEntity>()
                .set(AssistantPublishVersionEntity::getIsCurrent, 0)
                .eq(AssistantPublishVersionEntity::getIsCurrent, 1));
        target.setIsCurrent(1);
        assistantPublishVersionMapper.updateById(target);
        assistantDocumentMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<AssistantDocumentEntity>()
                .set(AssistantDocumentEntity::getPublishVersionId, null)
                .isNotNull(AssistantDocumentEntity::getPublishVersionId));
        List<Integer> documentIds = readDocumentIds(target.getDocumentIds());
        if (!documentIds.isEmpty()) {
            assistantDocumentMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<AssistantDocumentEntity>()
                    .set(AssistantDocumentEntity::getPublishVersionId, target.getId())
                    .in(AssistantDocumentEntity::getId, documentIds));
        }
        return toView(target);
    }

    public void removeDocumentReferences(List<Integer> removedDocumentIds) {
        if (removedDocumentIds == null || removedDocumentIds.isEmpty()) {
            return;
        }
        List<AssistantPublishVersionEntity> versions = assistantPublishVersionMapper.selectList(
                new LambdaQueryWrapper<AssistantPublishVersionEntity>().orderByDesc(AssistantPublishVersionEntity::getId)
        );
        for (AssistantPublishVersionEntity version : versions) {
            List<Integer> documentIds = new ArrayList<>(readDocumentIds(version.getDocumentIds()));
            if (documentIds.isEmpty()) {
                continue;
            }
            boolean changed = documentIds.removeIf(removedDocumentIds::contains);
            if (!changed) {
                continue;
            }
            version.setDocumentIds(writeDocumentIds(documentIds));
            assistantPublishVersionMapper.updateById(version);
        }
    }

    private AssistantDtos.AdminPublishVersionView toView(AssistantPublishVersionEntity entity) {
        return new AssistantDtos.AdminPublishVersionView(
                entity.getId(),
                entity.getVersionName(),
                entity.getNotes(),
                readDocumentIds(entity.getDocumentIds()),
                entity.getIsCurrent() != null && entity.getIsCurrent() == 1,
                entity.getPublishedBy(),
                entity.getCreateTime()
        );
    }

    private List<Integer> readDocumentIds(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(value, INTEGER_LIST);
        } catch (Exception exception) {
            return List.of();
        }
    }

    private String writeDocumentIds(List<Integer> ids) {
        try {
            return objectMapper.writeValueAsString(ids);
        } catch (Exception exception) {
            throw new ApiException(5000, "Failed to serialize publish version");
        }
    }
}
