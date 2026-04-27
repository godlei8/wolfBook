package com.wolfbook.backend.ai.service;

import com.wolfbook.backend.ai.model.AiFailure;
import com.wolfbook.backend.ai.model.AiFailureReason;
import org.springframework.stereotype.Component;

import java.net.SocketTimeoutException;
import java.util.Locale;
import java.util.concurrent.TimeoutException;

@Component
public class MiniMaxFailureClassifier {

    public AiFailure missingKey(String keyName) {
        return new AiFailure(AiFailureReason.UNCONFIGURED, "MiniMax 尚未配置，请设置 " + keyName + " 后再使用 AI 助手。", false);
    }

    public AiFailure classify(Throwable throwable) {
        if (throwable instanceof SocketTimeoutException || throwable instanceof TimeoutException) {
            return timeout();
        }
        return classify(0, throwable == null ? "" : throwable.getMessage());
    }

    public AiFailure classify(int statusCode, String message) {
        String normalized = message == null ? "" : message.toLowerCase(Locale.ROOT);
        if (statusCode == 401 || statusCode == 403 || normalized.contains("invalid api key") || normalized.contains("unauthorized")) {
            return new AiFailure(AiFailureReason.INVALID_KEY, "MiniMax key 无效或无权限，请检查后台配置。", false);
        }
        if (statusCode == 402 || normalized.contains("insufficient") || normalized.contains("balance") || normalized.contains("quota")) {
            return new AiFailure(AiFailureReason.QUOTA_EXCEEDED, "MiniMax 余额或额度不足，请充值或更换可用 key。", false);
        }
        if (statusCode == 429 || normalized.contains("rate limit") || normalized.contains("too many")) {
            return new AiFailure(AiFailureReason.RATE_LIMITED, "MiniMax 请求过于频繁，请稍后再试。", true);
        }
        if (statusCode == 408 || normalized.contains("timeout") || normalized.contains("timed out")) {
            return timeout();
        }
        if (statusCode >= 500 || normalized.contains("unavailable")) {
            return new AiFailure(AiFailureReason.SERVICE_UNAVAILABLE, "MiniMax 服务暂时不可用，请稍后再试。", true);
        }
        return new AiFailure(AiFailureReason.UNKNOWN, "AI 服务暂时不可用，请稍后再试。", true);
    }

    private AiFailure timeout() {
        return new AiFailure(AiFailureReason.TIMEOUT, "MiniMax 响应超时，请稍后再试。", true);
    }
}
