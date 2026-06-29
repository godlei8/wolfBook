package com.wolfbook.backend.controller.api;

import com.wolfbook.backend.common.ApiResponse;
import com.wolfbook.backend.dto.WolfbookDtos;
import com.wolfbook.backend.service.JudgeService;
import jakarta.validation.Valid;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 小程序法官局接口。
 *
 * <p>对外暴露创建房间、加入房间、读取房间状态、开始游戏、提交行动等实时房间操作。</p>
 */
@RestController
@RequestMapping("/api/judge")
@Validated
public class ApiJudgeController {

    private final JudgeService judgeService;

    public ApiJudgeController(JudgeService judgeService) {
        this.judgeService = judgeService;
    }

    @GetMapping("/rooms/recent")
    public ApiResponse<List<WolfbookDtos.JudgeRoomSummaryView>> listRecentRooms(
            @RequestHeader("Authorization") String authorization
    ) {
        return ApiResponse.success(judgeService.listRecentRooms(authorization));
    }

    @PostMapping("/rooms")
    public ApiResponse<WolfbookDtos.JudgeRoomSnapshotView> createRoom(
            @RequestHeader("Authorization") String authorization,
            @RequestBody @Valid WolfbookDtos.JudgeRoomCreateRequest request
    ) {
        return ApiResponse.success(judgeService.createRoom(authorization, request));
    }

    @GetMapping("/rooms/{roomId}")
    public ApiResponse<WolfbookDtos.JudgeRoomSnapshotView> getRoom(
            @RequestHeader("Authorization") String authorization,
            @PathVariable String roomId
    ) {
        return ApiResponse.success(judgeService.getRoomSnapshot(authorization, roomId));
    }

    @PostMapping("/rooms/{roomId}/join")
    public ApiResponse<WolfbookDtos.JudgeRoomSnapshotView> joinRoom(
            @RequestHeader("Authorization") String authorization,
            @PathVariable String roomId
    ) {
        return ApiResponse.success(judgeService.joinRoom(authorization, roomId));
    }

    @PostMapping("/rooms/{roomId}/ready")
    public ApiResponse<WolfbookDtos.JudgeRoomSnapshotView> toggleReady(
            @RequestHeader("Authorization") String authorization,
            @PathVariable String roomId
    ) {
        return ApiResponse.success(judgeService.toggleReady(authorization, roomId));
    }

    @PostMapping("/rooms/{roomId}/start")
    public ApiResponse<WolfbookDtos.JudgeRoomSnapshotView> startRoom(
            @RequestHeader("Authorization") String authorization,
            @PathVariable String roomId
    ) {
        return ApiResponse.success(judgeService.startRoom(authorization, roomId));
    }

    @PostMapping("/rooms/{roomId}/advance")
    public ApiResponse<WolfbookDtos.JudgeRoomSnapshotView> advanceRoom(
            @RequestHeader("Authorization") String authorization,
            @PathVariable String roomId,
            @RequestBody @Valid WolfbookDtos.JudgeRoomAdvanceRequest request
    ) {
        return ApiResponse.success(judgeService.advanceRoom(authorization, roomId, request));
    }

    @PostMapping("/rooms/{roomId}/night-action")
    public ApiResponse<WolfbookDtos.JudgeRoomSnapshotView> submitNightAction(
            @RequestHeader("Authorization") String authorization,
            @PathVariable String roomId,
            @RequestBody @Valid WolfbookDtos.JudgeNightActionRequest request
    ) {
        return ApiResponse.success(judgeService.submitNightAction(authorization, roomId, request));
    }

    @PostMapping("/rooms/{roomId}/vote")
    public ApiResponse<WolfbookDtos.JudgeRoomSnapshotView> submitVote(
            @RequestHeader("Authorization") String authorization,
            @PathVariable String roomId,
            @RequestBody @Valid WolfbookDtos.JudgeVoteRequest request
    ) {
        return ApiResponse.success(judgeService.submitVote(authorization, roomId, request));
    }
}
