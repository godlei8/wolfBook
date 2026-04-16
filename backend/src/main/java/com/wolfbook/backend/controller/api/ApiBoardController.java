package com.wolfbook.backend.controller.api;

import com.wolfbook.backend.common.ApiResponse;
import com.wolfbook.backend.common.PageResponse;
import com.wolfbook.backend.dto.WolfbookDtos;
import com.wolfbook.backend.service.BoardService;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 小程序板库接口。
 *
 * <p>提供板子列表、板子详情、角色资料和筛选项，主要服务首页板库和详情页。</p>
 */
@RestController
@RequestMapping("/api")
@Validated
public class ApiBoardController {

    private final BoardService boardService;

    public ApiBoardController(BoardService boardService) {
        this.boardService = boardService;
    }

    @GetMapping("/boards")
    public ApiResponse<PageResponse<WolfbookDtos.BoardCardView>> listBoards(
            @RequestParam(required = false) String playerCount,
            @RequestParam(required = false) String difficulty,
            @RequestParam(required = false) String tag,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "10") @Min(1) int size
    ) {
        return ApiResponse.success(boardService.listBoards(playerCount, difficulty, tag, keyword, page, size));
    }

    @GetMapping("/boards/{id}")
    public ApiResponse<WolfbookDtos.BoardDetailView> getBoard(@PathVariable Integer id) {
        return ApiResponse.success(boardService.getBoardDetail(id));
    }

    @GetMapping("/roles")
    public ApiResponse<List<WolfbookDtos.RoleListItemView>> listRoles(@RequestParam(required = false) String camp) {
        return ApiResponse.success(boardService.listRoles(camp));
    }

    @GetMapping("/roles/{id}")
    public ApiResponse<WolfbookDtos.RoleDetailView> getRole(@PathVariable Integer id) {
        return ApiResponse.success(boardService.getRoleDetail(id));
    }
}
