package com.wolfbook.backend.controller.admin;

import com.wolfbook.backend.common.ApiResponse;
import com.wolfbook.backend.dto.AssistantDtos;
import com.wolfbook.backend.service.AdminService;
import com.wolfbook.backend.service.assistant.AssistantConfigService;
import com.wolfbook.backend.service.assistant.AssistantKnowledgeService;
import com.wolfbook.backend.service.assistant.AssistantLogService;
import com.wolfbook.backend.service.assistant.AssistantPublishService;
import com.wolfbook.backend.support.TokenService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 管理后台 AI 助手接口。
 *
 * <p>负责 AI 配置、知识库上传审核、发布版本、查询日志和调试信息。
 * 小程序聊天接口不走这里，而是走 {@code ApiAssistantController}。</p>
 */
@RestController
@RequestMapping("/admin/ai")
public class AdminAiController {

    private final AdminService adminService;
    private final AssistantConfigService assistantConfigService;
    private final AssistantKnowledgeService assistantKnowledgeService;
    private final AssistantPublishService assistantPublishService;
    private final AssistantLogService assistantLogService;
    private final TokenService tokenService;

    public AdminAiController(
            AdminService adminService,
            AssistantConfigService assistantConfigService,
            AssistantKnowledgeService assistantKnowledgeService,
            AssistantPublishService assistantPublishService,
            AssistantLogService assistantLogService,
            TokenService tokenService
    ) {
        this.adminService = adminService;
        this.assistantConfigService = assistantConfigService;
        this.assistantKnowledgeService = assistantKnowledgeService;
        this.assistantPublishService = assistantPublishService;
        this.assistantLogService = assistantLogService;
        this.tokenService = tokenService;
    }

    @GetMapping("/config")
    public ApiResponse<AssistantDtos.AdminAiConfig> getConfig(@RequestHeader("Authorization") String authorization) {
        adminService.requireAdmin(authorization);
        return ApiResponse.success(assistantConfigService.getAdminConfig());
    }

    @PutMapping("/config")
    public ApiResponse<AssistantDtos.AdminAiConfig> saveConfig(
            @RequestHeader("Authorization") String authorization,
            @RequestBody AssistantDtos.AdminAiConfig request
    ) {
        adminService.requireAdmin(authorization);
        return ApiResponse.success(assistantConfigService.saveAdminConfig(request));
    }

    @GetMapping("/documents")
    public ApiResponse<List<AssistantDtos.AdminDocumentView>> listDocuments(@RequestHeader("Authorization") String authorization) {
        adminService.requireAdmin(authorization);
        return ApiResponse.success(assistantKnowledgeService.listDocuments());
    }

    @PostMapping("/documents")
    public ApiResponse<AssistantDtos.AdminDocumentView> uploadDocument(
            @RequestHeader("Authorization") String authorization,
            @RequestPart("file") MultipartFile file
    ) {
        adminService.requireAdmin(authorization);
        return ApiResponse.success(assistantKnowledgeService.uploadDocument(file));
    }

    @PatchMapping("/documents/{id}")
    public ApiResponse<AssistantDtos.AdminDocumentView> updateDocumentReview(
            @RequestHeader("Authorization") String authorization,
            @PathVariable Integer id,
            @RequestBody @Valid AssistantDtos.AdminDocumentUpdateRequest request
    ) {
        adminService.requireAdmin(authorization);
        return ApiResponse.success(assistantKnowledgeService.updateReviewStatus(id, request.reviewStatus()));
    }

    @PostMapping("/documents/{id}/reindex")
    public ApiResponse<AssistantDtos.AdminDocumentView> reindexDocument(
            @RequestHeader("Authorization") String authorization,
            @PathVariable Integer id
    ) {
        adminService.requireAdmin(authorization);
        return ApiResponse.success(assistantKnowledgeService.reindexDocument(id));
    }

    @DeleteMapping("/documents")
    public ApiResponse<Integer> clearDocuments(@RequestHeader("Authorization") String authorization) {
        adminService.requireAdmin(authorization);
        return ApiResponse.success(assistantKnowledgeService.clearUploadedDocuments());
    }

    @PostMapping("/publish")
    public ApiResponse<AssistantDtos.AdminPublishVersionView> publish(
            @RequestHeader("Authorization") String authorization,
            @RequestBody(required = false) AssistantDtos.AdminPublishRequest request
    ) {
        adminService.requireAdmin(authorization);
        assistantKnowledgeService.rebuildStructuredKnowledge();
        AssistantDtos.AdminPublishVersionView published = assistantPublishService.publish(
                tokenService.requireAdmin(authorization),
                request == null ? null : request.notes()
        );
        assistantKnowledgeService.syncPublishedKnowledgeVersion(published.id());
        return ApiResponse.success(published);
    }

    @PostMapping("/publish/rollback")
    public ApiResponse<AssistantDtos.AdminPublishVersionView> rollback(
            @RequestHeader("Authorization") String authorization,
            @RequestBody @Valid AssistantDtos.AdminRollbackRequest request
    ) {
        adminService.requireAdmin(authorization);
        AssistantDtos.AdminPublishVersionView rolledBack = assistantPublishService.rollback(request.versionId());
        assistantKnowledgeService.syncPublishedKnowledgeVersion(rolledBack.id());
        return ApiResponse.success(rolledBack);
    }

    @GetMapping("/publish")
    public ApiResponse<List<AssistantDtos.AdminPublishVersionView>> listVersions(@RequestHeader("Authorization") String authorization) {
        adminService.requireAdmin(authorization);
        return ApiResponse.success(assistantPublishService.listVersions());
    }

    @GetMapping("/logs")
    public ApiResponse<List<AssistantDtos.AdminQueryLogView>> listLogs(@RequestHeader("Authorization") String authorization) {
        adminService.requireAdmin(authorization);
        return ApiResponse.success(assistantLogService.listLogs());
    }

    @GetMapping("/summary")
    public ApiResponse<AssistantDtos.AdminAiPerformanceView> getSummary(@RequestHeader("Authorization") String authorization) {
        adminService.requireAdmin(authorization);
        return ApiResponse.success(assistantLogService.getPerformanceView());
    }

    @DeleteMapping("/logs")
    public ApiResponse<Integer> clearLogs(@RequestHeader("Authorization") String authorization) {
        adminService.requireAdmin(authorization);
        return ApiResponse.success(assistantLogService.clearLogs());
    }
}
