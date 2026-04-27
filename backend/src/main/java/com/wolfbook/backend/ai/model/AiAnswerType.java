package com.wolfbook.backend.ai.model;

public enum AiAnswerType {
    RAG_ANSWER,
    WEB_AUGMENTED_ANSWER,
    WEB_ONLY_ANSWER,
    CLARIFICATION,
    NO_EVIDENCE,
    OUT_OF_SCOPE,
    ERROR
}
