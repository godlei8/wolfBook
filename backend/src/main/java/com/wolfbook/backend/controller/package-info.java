/**
 * HTTP 接口入口层。
 *
 * <p>Controller 只做请求参数接收、鉴权透传和响应包装，真正的业务规则放在 service 包。
 * {@code controller.api} 面向小程序端，{@code controller.admin} 面向后台管理端。</p>
 */
package com.wolfbook.backend.controller;
