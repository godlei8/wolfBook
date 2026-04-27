package com.wolfbook.backend.ai.model;

public class AiServiceException extends RuntimeException {

    private final AiFailure failure;

    public AiServiceException(AiFailure failure) {
        super(failure.userMessage());
        this.failure = failure;
    }

    public AiFailure failure() {
        return failure;
    }
}
