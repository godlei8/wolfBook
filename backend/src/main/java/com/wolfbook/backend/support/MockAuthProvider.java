package com.wolfbook.backend.support;

import org.springframework.stereotype.Component;
import org.springframework.context.annotation.Profile;

@Component
@Profile("test")
public class MockAuthProvider implements AuthProvider {

    @Override
    public AuthUser exchangeCode(String code) {
        String normalized = code == null || code.isBlank() ? "mock-user" : code.trim();
        String suffix = Integer.toHexString(normalized.hashCode()).replace('-', '0');
        return new AuthUser(
                "wx_" + suffix,
                "夜行者" + suffix.substring(0, Math.min(4, suffix.length())),
                "https://picsum.photos/seed/" + suffix + "/300/300"
        );
    }
}
