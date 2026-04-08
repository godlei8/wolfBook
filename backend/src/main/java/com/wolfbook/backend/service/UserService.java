package com.wolfbook.backend.service;

import com.wolfbook.backend.common.ApiException;
import com.wolfbook.backend.domain.UserProfile;
import com.wolfbook.backend.dto.WolfbookDtos;
import com.wolfbook.backend.entity.UserEntity;
import com.wolfbook.backend.mapper.UserMapper;
import com.wolfbook.backend.support.AuthProvider;
import com.wolfbook.backend.support.DomainConverter;
import com.wolfbook.backend.support.TokenService;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
public class UserService {

    private final UserMapper userMapper;
    private final AuthProvider authProvider;
    private final TokenService tokenService;
    private final DomainConverter converter;

    public UserService(UserMapper userMapper, AuthProvider authProvider, TokenService tokenService, DomainConverter converter) {
        this.userMapper = userMapper;
        this.authProvider = authProvider;
        this.tokenService = tokenService;
        this.converter = converter;
    }

    public WolfbookDtos.LoginResponse login(String code) {
        AuthProvider.AuthUser authUser = authProvider.exchangeCode(code);
        UserEntity user = userMapper.selectById(authUser.openid());
        if (user == null) {
            user = new UserEntity();
            user.setOpenid(authUser.openid());
            user.setNickname(authUser.nickname());
            user.setAvatar(authUser.avatar());
            user.setStatus(1);
            user.setCreateTime(LocalDateTime.now());
            userMapper.insert(user);
        } else {
            user.setNickname(authUser.nickname());
            user.setAvatar(authUser.avatar());
            userMapper.updateById(user);
        }
        return new WolfbookDtos.LoginResponse(tokenService.issueUserToken(user.getOpenid()), toView(converter.toUserProfile(user)));
    }

    public WolfbookDtos.UserView getCurrentUser(String authorization) {
        return toView(requireUser(authorization));
    }

    public WolfbookDtos.UserView updateCurrentUser(String authorization, WolfbookDtos.UpdateUserRequest request) {
        UserEntity entity = getRequiredUserEntity(tokenService.requireUser(authorization));
        entity.setNickname(request.nickname());
        entity.setAvatar(request.avatar());
        userMapper.updateById(entity);
        return toView(converter.toUserProfile(entity));
    }

    public UserProfile requireUser(String authorization) {
        return converter.toUserProfile(getRequiredUserEntity(tokenService.requireUser(authorization)));
    }

    public WolfbookDtos.UserView toView(UserProfile user) {
        return new WolfbookDtos.UserView(user.openid(), user.nickname(), user.avatar(), user.status(), user.createTime());
    }

    private UserEntity getRequiredUserEntity(String openid) {
        UserEntity entity = userMapper.selectById(openid);
        if (entity == null) {
            throw new ApiException(4004, "用户不存在");
        }
        return entity;
    }
}
