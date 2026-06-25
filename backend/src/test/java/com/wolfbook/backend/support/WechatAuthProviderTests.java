package com.wolfbook.backend.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wolfbook.backend.common.ApiException;
import com.wolfbook.backend.config.WechatProperties;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class WechatAuthProviderTests {

    @Test
    void exchangeCodeShouldFallbackToMockUserWhenEnabledAndWechatCredentialsMissing() {
        WechatProperties properties = new WechatProperties();
        properties.getMiniProgram().setMockLoginEnabled(true);

        WechatAuthProvider provider = new WechatAuthProvider(properties, new ObjectMapper());

        AuthProvider.AuthUser authUser = provider.exchangeCode("dev-code");

        Assertions.assertTrue(authUser.openid().startsWith("wx_"));
        Assertions.assertFalse(authUser.nickname().isBlank());
        Assertions.assertTrue(authUser.avatar().contains("picsum.photos"));
    }

    @Test
    void exchangeCodeShouldRejectMissingCredentialsWhenMockLoginIsDisabled() {
        WechatProperties properties = new WechatProperties();
        properties.getMiniProgram().setMockLoginEnabled(false);

        WechatAuthProvider provider = new WechatAuthProvider(properties, new ObjectMapper());

        ApiException exception = Assertions.assertThrows(ApiException.class, () -> provider.exchangeCode("dev-code"));
        Assertions.assertEquals(5001, exception.getCode());
    }
}
