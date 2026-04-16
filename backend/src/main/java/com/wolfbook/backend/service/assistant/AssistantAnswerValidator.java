package com.wolfbook.backend.service.assistant;

import org.springframework.stereotype.Service;

import java.util.List;

/**
 * AI 助手答案校验器。
 *
 * <p>明确主体问题采用 fail-closed 策略：答案一旦出现未允许的角色/板子名称，
 * 就判为漂移，调用方必须重试或降级成模板直答。</p>
 */
@Service
class AssistantAnswerValidator {

    private final AssistantSubjectCatalog subjectCatalog;

    AssistantAnswerValidator(AssistantSubjectCatalog subjectCatalog) {
        this.subjectCatalog = subjectCatalog;
    }

    AssistantAnswerValidation validate(
            String answer,
            AssistantQueryPlan queryPlan,
            List<AssistantKnowledgeService.KnowledgeHit> hits
    ) {
        if (answer == null || answer.isBlank()) {
            return AssistantAnswerValidation.invalid("EMPTY_ANSWER");
        }
        if (queryPlan == null || !queryPlan.strictSubject() || queryPlan.subject() == null) {
            return AssistantAnswerValidation.ok();
        }
        if (subjectCatalog.containsOtherSubject(answer, queryPlan.subject(), List.of())) {
            return AssistantAnswerValidation.invalid("SUBJECT_DRIFT");
        }
        return AssistantAnswerValidation.ok();
    }
}
