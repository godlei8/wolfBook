package com.wolfbook.backend.support;

import com.wolfbook.backend.common.ApiException;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * 简化版 token 服务。
 *
 * <p>当前 token 用 Base64 编码身份类型和主体，适合本项目开发/轻量部署使用。
 * 如果后续接入正式登录体系，可以在这里替换为 JWT 或服务端 session。</p>
 */
@Component
public class TokenService {

    public String issueUserToken(String openid) {
        return encode("user", openid);
    }

    public String issueAdminToken(String username) {
        return encode("admin", username);
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

    private String encode(String kind, String subject) {
        return Base64.getUrlEncoder().encodeToString((kind + ":" + subject).getBytes(StandardCharsets.UTF_8));
    }

    private Token parse(String authorization) {
        if (authorization == null || authorization.isBlank()) {
            throw new ApiException(4001, "缺少 Authorization");
        }
        String raw = authorization.startsWith("Bearer ") ? authorization.substring(7) : authorization;
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(raw), StandardCharsets.UTF_8);
            String[] parts = decoded.split(":", 2);
            if (parts.length != 2) {
                throw new ApiException(4001, "无效 token");
            }
            return new Token(parts[0], parts[1]);
        } catch (IllegalArgumentException exception) {
            throw new ApiException(4001, "无效 token");
        }
    }

    private record Token(String kind, String subject) {
    }
}
