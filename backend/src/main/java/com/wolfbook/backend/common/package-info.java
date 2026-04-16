/**
 * 全局通用返回结构和异常处理。
 *
 * <p>这里的类不承载业务逻辑，主要统一 Controller 对外输出的格式：
 * {@code ApiResponse} 包装成功/失败响应，{@code PageResponse} 包装分页结果，
 * {@code ApiException} 和 {@code GlobalExceptionHandler} 负责把业务异常转成稳定的接口错误。</p>
 */
package com.wolfbook.backend.common;
