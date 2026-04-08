package com.wolfbook.backend.controller.api;

import com.wolfbook.backend.common.ApiResponse;
import com.wolfbook.backend.common.PageResponse;
import com.wolfbook.backend.dto.WolfbookDtos;
import com.wolfbook.backend.service.CommunityService;
import com.wolfbook.backend.support.UploadProvider;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api")
@Validated
public class ApiCommunityController {

    private final CommunityService communityService;
    private final UploadProvider uploadProvider;

    public ApiCommunityController(CommunityService communityService, UploadProvider uploadProvider) {
        this.communityService = communityService;
        this.uploadProvider = uploadProvider;
    }

    @GetMapping("/posts")
    public ApiResponse<PageResponse<WolfbookDtos.PostSummaryView>> listPosts(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "10") @Min(1) int size
    ) {
        return ApiResponse.success(communityService.listPosts(page, size, authorization));
    }

    @GetMapping("/posts/{id}")
    public ApiResponse<WolfbookDtos.PostDetailView> getPost(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @PathVariable Integer id
    ) {
        return ApiResponse.success(communityService.getPostDetail(id, authorization));
    }

    @PostMapping("/posts")
    public ApiResponse<WolfbookDtos.PostDetailView> createPost(
            @RequestHeader("Authorization") String authorization,
            @RequestBody @Valid WolfbookDtos.CreatePostRequest request
    ) {
        return ApiResponse.success(communityService.createPost(authorization, request));
    }

    @PostMapping("/posts/{id}/like")
    public ApiResponse<WolfbookDtos.ToggleLikeResponse> togglePostLike(
            @RequestHeader("Authorization") String authorization,
            @PathVariable Integer id
    ) {
        return ApiResponse.success(communityService.togglePostLike(authorization, id));
    }

    @PostMapping("/comments")
    public ApiResponse<WolfbookDtos.CommentView> createComment(
            @RequestHeader("Authorization") String authorization,
            @RequestBody @Valid WolfbookDtos.CreateCommentRequest request
    ) {
        return ApiResponse.success(communityService.createComment(authorization, request));
    }

    @PostMapping("/comments/{id}/like")
    public ApiResponse<WolfbookDtos.ToggleLikeResponse> toggleCommentLike(
            @RequestHeader("Authorization") String authorization,
            @PathVariable Integer id
    ) {
        return ApiResponse.success(communityService.toggleCommentLike(authorization, id));
    }

    @PostMapping("/reports")
    public ApiResponse<Void> createReport(
            @RequestHeader("Authorization") String authorization,
            @RequestBody @Valid WolfbookDtos.ReportRequest request
    ) {
        communityService.createReport(authorization, request);
        return ApiResponse.success();
    }

    @PostMapping("/upload")
    public ApiResponse<WolfbookDtos.UploadResponse> upload(@RequestHeader("Authorization") String authorization, @RequestPart("file") MultipartFile file) {
        return ApiResponse.success(new WolfbookDtos.UploadResponse(uploadProvider.upload(file, "community/media")));
    }
}
