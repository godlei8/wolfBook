package com.wolfbook.backend.service.assistant;

record AssistantGeneratedAnswer(
        String answer,
        String fallbackMode,
        long modelMs
) {
}
