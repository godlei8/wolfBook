package com.wolfbook.backend.controller.admin;

import com.wolfbook.backend.common.ApiResponse;
import com.wolfbook.backend.common.PageResponse;
import com.wolfbook.backend.domain.Board;
import com.wolfbook.backend.domain.Report;
import com.wolfbook.backend.dto.WolfbookDtos;
import com.wolfbook.backend.service.AdminService;
import com.wolfbook.backend.support.UploadProvider;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/admin")
public class AdminController {

    private final AdminService adminService;
    private final UploadProvider uploadProvider;

    public AdminController(AdminService adminService, UploadProvider uploadProvider) {
        this.adminService = adminService;
        this.uploadProvider = uploadProvider;
    }

    @PostMapping("/login")
    public ApiResponse<WolfbookDtos.LoginResponse> login(@RequestBody @Valid WolfbookDtos.AdminLoginRequest request) {
        return ApiResponse.success(adminService.login(request));
    }

    @GetMapping("/summary")
    public ApiResponse<WolfbookDtos.DashboardSummary> summary(@RequestHeader("Authorization") String authorization) {
        adminService.requireAdmin(authorization);
        return ApiResponse.success(adminService.summary());
    }

    @GetMapping("/boards")
    public ApiResponse<List<Board>> boards(@RequestHeader("Authorization") String authorization) {
        adminService.requireAdmin(authorization);
        return ApiResponse.success(adminService.listBoards());
    }

    @PostMapping("/boards")
    public ApiResponse<Board> createBoard(@RequestHeader("Authorization") String authorization, @RequestBody @Valid WolfbookDtos.AdminBoardRequest request) {
        adminService.requireAdmin(authorization);
        return ApiResponse.success(adminService.saveBoard(null, request));
    }

    @PutMapping("/boards/{id}")
    public ApiResponse<Board> updateBoard(@RequestHeader("Authorization") String authorization, @PathVariable Integer id, @RequestBody @Valid WolfbookDtos.AdminBoardRequest request) {
        adminService.requireAdmin(authorization);
        return ApiResponse.success(adminService.saveBoard(id, request));
    }

    @DeleteMapping("/boards/{id}")
    public ApiResponse<Void> deleteBoard(@RequestHeader("Authorization") String authorization, @PathVariable Integer id) {
        adminService.requireAdmin(authorization);
        adminService.deleteBoard(id);
        return ApiResponse.success();
    }

    @GetMapping("/roles")
    public ApiResponse<List<WolfbookDtos.AdminRoleView>> roles(@RequestHeader("Authorization") String authorization) {
        adminService.requireAdmin(authorization);
        return ApiResponse.success(adminService.listRoles());
    }

    @PostMapping("/roles")
    public ApiResponse<WolfbookDtos.AdminRoleView> createRole(@RequestHeader("Authorization") String authorization, @RequestBody @Valid WolfbookDtos.AdminRoleRequest request) {
        adminService.requireAdmin(authorization);
        return ApiResponse.success(adminService.saveRole(null, request));
    }

    @PutMapping("/roles/{id}")
    public ApiResponse<WolfbookDtos.AdminRoleView> updateRole(@RequestHeader("Authorization") String authorization, @PathVariable Integer id, @RequestBody @Valid WolfbookDtos.AdminRoleRequest request) {
        adminService.requireAdmin(authorization);
        return ApiResponse.success(adminService.saveRole(id, request));
    }

    @DeleteMapping("/roles/{id}")
    public ApiResponse<Void> deleteRole(@RequestHeader("Authorization") String authorization, @PathVariable Integer id) {
        adminService.requireAdmin(authorization);
        adminService.deleteRole(id);
        return ApiResponse.success();
    }

    @GetMapping("/posts")
    public ApiResponse<PageResponse<WolfbookDtos.PostSummaryView>> posts(@RequestHeader("Authorization") String authorization, @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int size) {
        adminService.requireAdmin(authorization);
        return ApiResponse.success(adminService.listPosts(page, size));
    }

    @PatchMapping("/posts/{id}/status")
    public ApiResponse<Void> updatePostStatus(@RequestHeader("Authorization") String authorization, @PathVariable Integer id, @RequestBody @Valid WolfbookDtos.AdminStatusRequest request) {
        adminService.requireAdmin(authorization);
        adminService.updatePostStatus(id, request.status());
        return ApiResponse.success();
    }

    @DeleteMapping("/posts/{id}")
    public ApiResponse<Void> deletePost(@RequestHeader("Authorization") String authorization, @PathVariable Integer id) {
        adminService.requireAdmin(authorization);
        adminService.deletePost(id);
        return ApiResponse.success();
    }

    @GetMapping("/comments")
    public ApiResponse<PageResponse<WolfbookDtos.CommentView>> comments(@RequestHeader("Authorization") String authorization, @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int size) {
        adminService.requireAdmin(authorization);
        return ApiResponse.success(adminService.listComments(page, size));
    }

    @DeleteMapping("/comments/{id}")
    public ApiResponse<Void> deleteComment(@RequestHeader("Authorization") String authorization, @PathVariable Integer id) {
        adminService.requireAdmin(authorization);
        adminService.deleteComment(id);
        return ApiResponse.success();
    }

    @GetMapping("/reports")
    public ApiResponse<PageResponse<Report>> reports(@RequestHeader("Authorization") String authorization, @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int size) {
        adminService.requireAdmin(authorization);
        return ApiResponse.success(adminService.listReports(page, size));
    }

    @PatchMapping("/reports/{id}")
    public ApiResponse<Report> processReport(@RequestHeader("Authorization") String authorization, @PathVariable Integer id, @RequestBody @Valid WolfbookDtos.ReportProcessRequest request) {
        return ApiResponse.success(adminService.processReport(id, request, authorization));
    }

    @PostMapping("/upload")
    public ApiResponse<WolfbookDtos.UploadResponse> upload(@RequestHeader("Authorization") String authorization, @RequestPart("file") MultipartFile file) {
        adminService.requireAdmin(authorization);
        return ApiResponse.success(new WolfbookDtos.UploadResponse(uploadProvider.upload(file)));
    }
}
