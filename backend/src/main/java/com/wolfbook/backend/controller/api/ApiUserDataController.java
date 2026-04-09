package com.wolfbook.backend.controller.api;

import com.wolfbook.backend.common.ApiResponse;
import com.wolfbook.backend.dto.WolfbookDtos;
import com.wolfbook.backend.service.UserDataService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/user")
@Validated
public class ApiUserDataController {

    private final UserDataService userDataService;

    public ApiUserDataController(UserDataService userDataService) {
        this.userDataService = userDataService;
    }

    @GetMapping("/favorites")
    public ApiResponse<WolfbookDtos.FavoriteBoardsView> getFavorites(@RequestHeader("Authorization") String authorization) {
        return ApiResponse.success(userDataService.getFavoriteBoards(authorization));
    }

    @PostMapping("/favorites/{boardId}")
    public ApiResponse<WolfbookDtos.FavoriteBoardsView> addFavorite(
            @RequestHeader("Authorization") String authorization,
            @PathVariable @NotNull Integer boardId
    ) {
        return ApiResponse.success(userDataService.addFavoriteBoard(authorization, boardId));
    }

    @DeleteMapping("/favorites/{boardId}")
    public ApiResponse<WolfbookDtos.FavoriteBoardsView> removeFavorite(
            @RequestHeader("Authorization") String authorization,
            @PathVariable @NotNull Integer boardId
    ) {
        return ApiResponse.success(userDataService.removeFavoriteBoard(authorization, boardId));
    }

    @GetMapping("/sessions")
    public ApiResponse<List<WolfbookDtos.NoteSessionView>> listSessions(@RequestHeader("Authorization") String authorization) {
        return ApiResponse.success(userDataService.listNoteSessions(authorization));
    }

    @GetMapping("/sessions/{sessionId}")
    public ApiResponse<WolfbookDtos.NoteSessionView> getSession(
            @RequestHeader("Authorization") String authorization,
            @PathVariable String sessionId
    ) {
        return ApiResponse.success(userDataService.getNoteSession(authorization, sessionId));
    }

    @PutMapping("/sessions/{sessionId}")
    public ApiResponse<WolfbookDtos.NoteSessionView> saveSession(
            @RequestHeader("Authorization") String authorization,
            @PathVariable String sessionId,
            @RequestBody @Valid WolfbookDtos.NoteSessionSaveRequest request
    ) {
        return ApiResponse.success(userDataService.saveNoteSession(authorization, sessionId, request));
    }

    @DeleteMapping("/sessions/{sessionId}")
    public ApiResponse<Void> deleteSession(
            @RequestHeader("Authorization") String authorization,
            @PathVariable String sessionId
    ) {
        userDataService.deleteNoteSession(authorization, sessionId);
        return ApiResponse.success();
    }
}
