package com.wolfbook.backend.controller.api;

import com.wolfbook.backend.common.ApiResponse;
import com.wolfbook.backend.dto.WolfbookDtos;
import com.wolfbook.backend.service.WorldService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class ApiWorldController {

    private final WorldService worldService;

    public ApiWorldController(WorldService worldService) {
        this.worldService = worldService;
    }

    /** 夜镇编年史：小镇简介、三方势力、编年史事件（出场角色已展开成头像）。 */
    @GetMapping("/world")
    public ApiResponse<WolfbookDtos.WorldLoreView> world() {
        return ApiResponse.success(worldService.getView());
    }
}
