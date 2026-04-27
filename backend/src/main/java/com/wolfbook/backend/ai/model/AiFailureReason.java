package com.wolfbook.backend.ai.model;

public enum AiFailureReason {
    UNCONFIGURED,
    INVALID_KEY,
    QUOTA_EXCEEDED,
    RATE_LIMITED,
    TIMEOUT,
    SERVICE_UNAVAILABLE,
    UNKNOWN
}
