package com.wolfbook.backend.ai.model;

public record AiFailure(AiFailureReason reason, String userMessage, boolean retryable) {
}
