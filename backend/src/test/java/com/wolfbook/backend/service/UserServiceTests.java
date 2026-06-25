package com.wolfbook.backend.service;

import com.wolfbook.backend.domain.UserProfile;
import com.wolfbook.backend.entity.UserEntity;
import com.wolfbook.backend.support.AuthProvider;
import com.wolfbook.backend.support.DomainConverter;
import com.wolfbook.backend.support.TokenService;
import com.wolfbook.backend.mapper.UserMapper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

import java.time.LocalDateTime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserServiceTests {

    @Test
    void loginShouldRecoverWhenUserIsCreatedConcurrently() {
        UserMapper userMapper = mock(UserMapper.class);
        AuthProvider authProvider = mock(AuthProvider.class);
        TokenService tokenService = mock(TokenService.class);
        DomainConverter converter = mock(DomainConverter.class);
        UserService userService = new UserService(userMapper, authProvider, tokenService, converter);

        AuthProvider.AuthUser authUser = new AuthProvider.AuthUser("wx_race", "并发用户", "https://avatar");
        UserEntity existingUser = new UserEntity();
        existingUser.setOpenid("wx_race");
        existingUser.setNickname("并发用户");
        existingUser.setAvatar("https://avatar");
        existingUser.setStatus(1);
        existingUser.setCreateTime(LocalDateTime.now());
        UserProfile profile = new UserProfile(
                existingUser.getOpenid(),
                existingUser.getNickname(),
                existingUser.getAvatar(),
                existingUser.getStatus(),
                existingUser.getCreateTime()
        );

        when(authProvider.exchangeCode("race-code")).thenReturn(authUser);
        when(userMapper.selectById("wx_race")).thenReturn(null, existingUser);
        doThrow(new DuplicateKeyException("duplicate")).when(userMapper).insert(any(UserEntity.class));
        when(tokenService.issueUserToken("wx_race")).thenReturn("user-token");
        when(converter.toUserProfile(existingUser)).thenReturn(profile);

        var response = userService.login("race-code");

        Assertions.assertEquals("user-token", response.token());
        Assertions.assertEquals("wx_race", response.user().openid());
        verify(userMapper, times(2)).selectById("wx_race");
    }
}
