package com.wolfbook.backend.ai;

import com.wolfbook.backend.ai.model.AiFailureReason;
import com.wolfbook.backend.ai.service.MiniMaxFailureClassifier;
import org.junit.jupiter.api.Test;

import java.net.SocketTimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

class MiniMaxFailureClassifierTest {

    private final MiniMaxFailureClassifier classifier = new MiniMaxFailureClassifier();

    @Test
    void classifiesQuotaAndRateLimitSeparately() {
        assertThat(classifier.classify(402, "insufficient balance").reason()).isEqualTo(AiFailureReason.QUOTA_EXCEEDED);
        assertThat(classifier.classify(429, "too many requests").reason()).isEqualTo(AiFailureReason.RATE_LIMITED);
    }

    @Test
    void classifiesTimeoutAndMissingKey() {
        assertThat(classifier.classify(new SocketTimeoutException("read timed out")).reason()).isEqualTo(AiFailureReason.TIMEOUT);
        assertThat(classifier.missingKey("MINIMAX_CHAT_API_KEY").reason()).isEqualTo(AiFailureReason.UNCONFIGURED);
    }
}
