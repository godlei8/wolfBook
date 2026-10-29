package com.wolfbook.backend.support;

import com.wolfbook.backend.common.ApiException;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

class TokenServiceTests {

    private final TokenService tokenService = new TokenService("unit-test-secret", 30);

    @Test
    void issuedTokensRoundTrip() {
        String userToken = tokenService.issueUserToken("wx_openid");
        String adminToken = tokenService.issueAdminToken("admin");

        Assertions.assertEquals("wx_openid", tokenService.requireUser("Bearer " + userToken));
        Assertions.assertEquals("admin", tokenService.requireAdmin(adminToken));
    }

    @Test
    void unsignedBase64TokenIsRejected() {
        String forged = Base64.getUrlEncoder().encodeToString("admin:anyone".getBytes(StandardCharsets.UTF_8));

        ApiException exception = Assertions.assertThrows(ApiException.class, () -> tokenService.requireAdmin("Bearer " + forged));
        Assertions.assertEquals(4001, exception.getCode());
        Assertions.assertNull(tokenService.resolveUser("Bearer " + forged));
    }

    @Test
    void tokenSignedWithAnotherSecretIsRejected() {
        String token = new TokenService("other-secret", 30).issueAdminToken("admin");

        ApiException exception = Assertions.assertThrows(ApiException.class, () -> tokenService.requireAdmin(token));
        Assertions.assertEquals(4001, exception.getCode());
    }

    @Test
    void tamperedPayloadIsRejected() {
        String token = tokenService.issueUserToken("wx_a");
        String signature = token.substring(token.lastIndexOf('.'));
        String tampered = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("user:wx_b:9999999999".getBytes(StandardCharsets.UTF_8)) + signature;

        Assertions.assertThrows(ApiException.class, () -> tokenService.requireUser(tampered));
    }

    @Test
    void expiredTokenIsRejected() {
        TokenService shortLived = new TokenService("unit-test-secret", 30);
        String token = shortLived.issueToken("user", "wx_old", Duration.ofSeconds(-1));

        ApiException exception = Assertions.assertThrows(ApiException.class, () -> tokenService.requireUser(token));
        Assertions.assertEquals(4001, exception.getCode());
    }

    @Test
    void userTokenCannotBeUsedAsAdmin() {
        String token = tokenService.issueUserToken("wx_openid");

        Assertions.assertThrows(ApiException.class, () -> tokenService.requireAdmin(token));
    }

    @Test
    void blankSecretIsRefused() {
        Assertions.assertThrows(IllegalStateException.class, () -> new TokenService(" ", 30));
    }
}
