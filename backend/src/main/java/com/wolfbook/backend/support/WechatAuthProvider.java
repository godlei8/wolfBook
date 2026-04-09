package com.wolfbook.backend.support;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.wolfbook.backend.common.ApiException;
import com.wolfbook.backend.config.WechatProperties;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;

@Component
@Profile("!test")
public class WechatAuthProvider implements AuthProvider {

    private final WechatProperties wechatProperties;
    private final RestClient restClient;

    public WechatAuthProvider(WechatProperties wechatProperties) {
        this.wechatProperties = wechatProperties;
        this.restClient = RestClient.builder().build();
    }

    @Override
    public AuthUser exchangeCode(String code) {
        if (!StringUtils.hasText(code)) {
            throw new ApiException(4000, "缺少微信登录 code");
        }
        WechatProperties.MiniProgramProperties miniProgram = wechatProperties.getMiniProgram();
        if (!StringUtils.hasText(miniProgram.getAppId()) || !StringUtils.hasText(miniProgram.getAppSecret())) {
            throw new ApiException(5001, "微信登录未配置 appId 或 appSecret");
        }

        URI uri = UriComponentsBuilder.fromHttpUrl(miniProgram.getCode2SessionUrl())
                .queryParam("appid", miniProgram.getAppId())
                .queryParam("secret", miniProgram.getAppSecret())
                .queryParam("js_code", code)
                .queryParam("grant_type", "authorization_code")
                .build(true)
                .toUri();

        WechatCode2SessionResponse response;
        try {
            response = restClient.get()
                    .uri(uri)
                    .retrieve()
                    .body(WechatCode2SessionResponse.class);
        } catch (Exception exception) {
            throw new ApiException(5001, "微信登录服务请求失败，请稍后重试");
        }

        if (response == null) {
            throw new ApiException(5001, "微信登录服务暂不可用");
        }
        if (response.errcode() != null && response.errcode() != 0) {
            throw new ApiException(4001, mapWechatError(response.errcode(), response.errmsg()));
        }
        if (!StringUtils.hasText(response.openid())) {
            throw new ApiException(5001, "微信登录未返回 openid");
        }
        return new AuthUser(response.openid(), "", "");
    }

    private String mapWechatError(Integer errcode, String errmsg) {
        return switch (errcode) {
            case 40029 -> "微信登录凭证无效，请重新登录";
            case 40163 -> "微信登录 code 已被使用，请重新发起登录";
            case 45011 -> "微信登录请求过于频繁，请稍后再试";
            default -> StringUtils.hasText(errmsg)
                    ? "微信登录失败：" + errmsg
                    : "微信登录失败，错误码 " + errcode;
        };
    }

    private record WechatCode2SessionResponse(
            String openid,
            @JsonProperty("session_key") String sessionKey,
            String unionid,
            Integer errcode,
            String errmsg
    ) {
    }
}
