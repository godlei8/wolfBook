package com.wolfbook.backend.support;

public interface AuthProvider {

    AuthUser exchangeCode(String code);

    record AuthUser(String openid, String nickname, String avatar) {
    }
}
