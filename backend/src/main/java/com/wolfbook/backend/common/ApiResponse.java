package com.wolfbook.backend.common;

public record ApiResponse<T>(int code, String msg, T data) {

    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(0, "success", data);
    }

    public static ApiResponse<Void> success() {
        return success(null);
    }

    public static <T> ApiResponse<T> failure(int code, String msg) {
        return new ApiResponse<>(code, msg, null);
    }
}
