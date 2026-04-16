package com.wolfbook.backend.controller.api;

import com.wolfbook.backend.common.ApiResponse;
import com.wolfbook.backend.dto.WolfbookDtos;
import com.wolfbook.backend.service.CommunityService;
import com.wolfbook.backend.support.UploadProvider;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 小程序社区接口。
 *
 * <p>负责帖子浏览、发布、评论、点赞、收藏、举报和图片上传。
 * 具体审核、计数和权限逻辑在 {@code CommunityService}。</p>
 */
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
    public ApiResponse<WolfbookDtos.CommunityFeedView> listPosts(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @RequestParam(defaultValue = "recommend") String tab,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) Integer boardId,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "hot") String sort,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "10") @Min(1) int size
    ) {
        return ApiResponse.success(communityService.listPosts(tab, type, boardId, q, sort, page, size, authorization));
    }

    @GetMapping("/posts/search")
    public ApiResponse<WolfbookDtos.CommunityFeedView> searchPosts(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @RequestParam String q,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) Integer boardId,
            @RequestParam(defaultValue = "hot") String sort,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "10") @Min(1) int size
    ) {
        return ApiResponse.success(communityService.listPosts("recommend", type, boardId, q, sort, page, size, authorization));
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

    @DeleteMapping("/posts/{id}")
    public ApiResponse<Void> deletePost(
            @RequestHeader("Authorization") String authorization,
            @PathVariable Integer id
    ) {
        communityService.deleteOwnPost(authorization, id);
        return ApiResponse.success();
    }

    @PostMapping("/posts/{id}/like")
    public ApiResponse<WolfbookDtos.ToggleLikeResponse> togglePostLike(
            @RequestHeader("Authorization") String authorization,
            @PathVariable Integer id
    ) {
        return ApiResponse.success(communityService.togglePostLike(authorization, id));
    }

    @PostMapping("/posts/{id}/favorite")
    public ApiResponse<WolfbookDtos.ToggleFavoriteResponse> favoritePost(
            @RequestHeader("Authorization") String authorization,
            @PathVariable Integer id
    ) {
        return ApiResponse.success(communityService.setPostFavorite(authorization, id, true));
    }

    @DeleteMapping("/posts/{id}/favorite")
    public ApiResponse<WolfbookDtos.ToggleFavoriteResponse> unfavoritePost(
            @RequestHeader("Authorization") String authorization,
            @PathVariable Integer id
    ) {
        return ApiResponse.success(communityService.setPostFavorite(authorization, id, false));
    }

    @PostMapping("/comments")
    public ApiResponse<WolfbookDtos.CommentView> createComment(
            @RequestHeader("Authorization") String authorization,
            @RequestBody @Valid WolfbookDtos.CreateCommentRequest request
    ) {
        return ApiResponse.success(communityService.createComment(authorization, request));
    }

    @DeleteMapping("/comments/{id}")
    public ApiResponse<Void> deleteComment(
            @RequestHeader("Authorization") String authorization,
            @PathVariable Integer id
    ) {
        communityService.deleteOwnComment(authorization, id);
        return ApiResponse.success();
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
    public ApiResponse<WolfbookDtos.UploadResponse> upload(
            @RequestHeader("Authorization") String authorization,
            @org.springframework.web.bind.annotation.RequestPart("file") MultipartFile file
    ) {
        return ApiResponse.success(new WolfbookDtos.UploadResponse(uploadProvider.upload(file, "community/media")));
    }
}
