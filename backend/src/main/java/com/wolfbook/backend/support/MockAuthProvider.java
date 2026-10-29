package com.wolfbook.backend.support;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("test")
public class MockAuthProvider implements AuthProvider {

    @Override
    public AuthUser exchangeCode(String code) {
        return MockWechatAuthSupport.buildAuthUser(code);
    }
}
