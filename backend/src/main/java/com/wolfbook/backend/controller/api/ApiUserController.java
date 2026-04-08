package com.wolfbook.backend.controller.api;

import com.wolfbook.backend.common.ApiResponse;
import com.wolfbook.backend.dto.WolfbookDtos;
import com.wolfbook.backend.service.UserService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class ApiUserController {

    private final UserService userService;

    public ApiUserController(UserService userService) {
        this.userService = userService;
    }

    @PostMapping("/login")
    public ApiResponse<WolfbookDtos.LoginResponse> login(@RequestBody @Valid WolfbookDtos.LoginRequest request) {
        return ApiResponse.success(userService.login(request.code()));
    }

    @GetMapping("/user/info")
    public ApiResponse<WolfbookDtos.UserView> userInfo(@RequestHeader("Authorization") String authorization) {
        return ApiResponse.success(userService.getCurrentUser(authorization));
    }

    @PutMapping("/user/info")
    public ApiResponse<WolfbookDtos.UserView> updateUser(
            @RequestHeader("Authorization") String authorization,
            @RequestBody @Valid WolfbookDtos.UpdateUserRequest request
    ) {
        return ApiResponse.success(userService.updateCurrentUser(authorization, request));
    }
}
