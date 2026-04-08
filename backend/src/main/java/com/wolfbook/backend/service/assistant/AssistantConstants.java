package com.wolfbook.backend.service.assistant;

final class AssistantConstants {

    static final String SOURCE_DOCUMENT = "DOCUMENT";
    static final String SOURCE_STRUCTURED = "STRUCTURED";
    static final String SOURCE_WEB = "WEB";

    static final String STATUS_READY = "READY";
    static final String STATUS_FAILED = "FAILED";

    static final String REVIEW_PENDING = "PENDING";
    static final String REVIEW_APPROVED = "APPROVED";
    static final String REVIEW_REJECTED = "REJECTED";

    static final String ANSWER_STRUCTURED = "STRUCTURED_RECOMMENDATION";
    static final String ANSWER_RAG = "RAG_ANSWER";
    static final String ANSWER_WEB = "WEB_AUGMENTED_ANSWER";
    static final String ANSWER_REFUSAL = "REFUSAL";

    private AssistantConstants() {
    }
}
