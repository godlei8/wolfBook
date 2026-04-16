package com.wolfbook.backend.service.assistant;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wolfbook.backend.entity.AssistantDocumentEntity;
import com.wolfbook.backend.mapper.AssistantDocumentMapper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

@Component
class AssistantKnowledgeAuditTask {

    private final AssistantDocumentMapper assistantDocumentMapper;

    AssistantKnowledgeAuditTask(AssistantDocumentMapper assistantDocumentMapper) {
        this.assistantDocumentMapper = assistantDocumentMapper;
    }

    @Scheduled(cron = "0 0 3 * * *")
    void auditExpiredDocuments() {
        LocalDateTime now = LocalDateTime.now();
        List<AssistantDocumentEntity> expiredDocuments = assistantDocumentMapper.selectList(
                new LambdaQueryWrapper<AssistantDocumentEntity>()
                        .eq(AssistantDocumentEntity::getSourceType, AssistantConstants.SOURCE_DOCUMENT)
                        .ne(AssistantDocumentEntity::getArchived, 1)
                        .eq(AssistantDocumentEntity::getReviewStatus, AssistantConstants.REVIEW_APPROVED)
                        .isNotNull(AssistantDocumentEntity::getExpiresAt)
                        .le(AssistantDocumentEntity::getExpiresAt, now)
        );
        for (AssistantDocumentEntity document : expiredDocuments) {
            document.setReviewStatus(AssistantConstants.REVIEW_NEEDS_REVIEW);
            document.setUpdateTime(now);
            assistantDocumentMapper.updateById(document);
        }
    }
}

