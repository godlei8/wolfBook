package com.wolfbook.backend.service.assistant;

record AssistantAnswerValidation(
        boolean valid,
        String reason
) {
    static AssistantAnswerValidation ok() {
        return new AssistantAnswerValidation(true, "OK");
    }

    static AssistantAnswerValidation invalid(String reason) {
        return new AssistantAnswerValidation(false, reason);
    }
}
