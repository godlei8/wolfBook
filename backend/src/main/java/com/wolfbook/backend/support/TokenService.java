package com.wolfbook.backend.support;

import com.wolfbook.backend.common.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

/**
 * 签发并校验形如 {@code base64url(kind:subject:expiresAt).base64url(hmacSha256)} 的 token。
 * 没有签名或签名不匹配的 token 一律视为未登录。
 */
@Component
public class TokenService {

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private final byte[] secret;
    private final Duration defaultTtl;

    public TokenService(
            @Value("${wolfbook.auth.token-secret:}") String secret,
            @Value("${wolfbook.auth.token-ttl-days:30}") long ttlDays
    ) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("wolfbook.auth.token-secret 未配置，无法签发登录 token");
        }
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.defaultTtl = Duration.ofDays(Math.max(ttlDays, 1));
    }

    public String issueUserToken(String openid) {
        return issueToken("user", openid, defaultTtl);
    }

    public String issueAdminToken(String username) {
        return issueToken("admin", username, defaultTtl);
    }

    public String issueToken(String kind, String subject, Duration ttl) {
        long expiresAt = Instant.now().plus(ttl).getEpochSecond();
        String payload = ENCODER.encodeToString((kind + ":" + subject + ":" + expiresAt).getBytes(StandardCharsets.UTF_8));
        return payload + "." + sign(payload);
    }

    public String requireUser(String authorization) {
        Token token = parse(authorization);
        if (!"user".equals(token.kind())) {
            throw new ApiException(4001, "请先登录");
        }
        return token.subject();
    }

    public String requireAdmin(String authorization) {
        Token token = parse(authorization);
        if (!"admin".equals(token.kind())) {
            throw new ApiException(4001, "管理员未登录");
        }
        return token.subject();
    }

    public String resolveUser(String authorization) {
        try {
            Token token = parse(authorization);
            return "user".equals(token.kind()) ? token.subject() : null;
        } catch (ApiException ignored) {
            return null;
        }
    }

    private Token parse(String authorization) {
        if (authorization == null || authorization.isBlank()) {
            throw new ApiException(4001, "缺少 Authorization");
        }
        String raw = authorization.startsWith("Bearer ") ? authorization.substring(7).trim() : authorization.trim();
        int dot = raw.lastIndexOf('.');
        if (dot <= 0 || dot == raw.length() - 1) {
            throw new ApiException(4001, "无效 token");
        }
        String payload = raw.substring(0, dot);
        String signature = raw.substring(dot + 1);
        if (!constantTimeEquals(sign(payload), signature)) {
            throw new ApiException(4001, "无效 token");
        }
        String[] parts;
        try {
            parts = new String(DECODER.decode(payload), StandardCharsets.UTF_8).split(":", 3);
        } catch (IllegalArgumentException exception) {
            throw new ApiException(4001, "无效 token");
        }
        if (parts.length != 3) {
            throw new ApiException(4001, "无效 token");
        }
        long expiresAt;
        try {
            expiresAt = Long.parseLong(parts[2]);
        } catch (NumberFormatException exception) {
            throw new ApiException(4001, "无效 token");
        }
        if (expiresAt < Instant.now().getEpochSecond()) {
            throw new ApiException(4001, "登录已过期，请重新登录");
        }
        return new Token(parts[0], parts[1]);
    }

    private String sign(String payload) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secret, HMAC_ALGORITHM));
            return ENCODER.encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException | InvalidKeyException exception) {
            throw new IllegalStateException("无法签名 token", exception);
        }
    }

    private static boolean constantTimeEquals(String expected, String actual) {
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8)
        );
    }

    private record Token(String kind, String subject) {
    }
}
