package com.wolfbook.backend.ai.controller;

import com.wolfbook.backend.ai.dto.AiDtos;
import com.wolfbook.backend.ai.service.AiAssistantService;
import com.wolfbook.backend.ai.service.AiDocumentService;
import com.wolfbook.backend.common.ApiResponse;
import com.wolfbook.backend.service.AdminService;
import com.wolfbook.backend.support.TokenService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/admin/ai")
public class AdminAiController {

    private final AdminService adminService;
    private final AiAssistantService assistantService;
    private final AiDocumentService documentService;
    private final TokenService tokenService;

    public AdminAiController(AdminService adminService, AiAssistantService assistantService, AiDocumentService documentService, TokenService tokenService) {
        this.adminService = adminService;
        this.assistantService = assistantService;
        this.documentService = documentService;
        this.tokenService = tokenService;
    }

    @GetMapping("/config")
    public ApiResponse<AiDtos.AdminAiConfigView> config(@RequestHeader("Authorization") String authorization) {
        adminService.requireAdmin(authorization);
        return ApiResponse.success(assistantService.getAdminConfig());
    }

    @PutMapping("/config")
    public ApiResponse<AiDtos.AdminAiConfigView> updateConfig(
            @RequestHeader("Authorization") String authorization,
            @RequestBody AiDtos.AdminAiConfigRequest request
    ) {
        adminService.requireAdmin(authorization);
        return ApiResponse.success(assistantService.updateAdminConfig(request));
    }

    @GetMapping("/documents")
    public ApiResponse<List<AiDtos.AdminAiDocumentView>> documents(@RequestHeader("Authorization") String authorization) {
        adminService.requireAdmin(authorization);
        return ApiResponse.success(documentService.listDocuments());
    }

    @PostMapping("/documents")
    public ApiResponse<AiDtos.AdminAiDocumentView> uploadDocument(
            @RequestHeader("Authorization") String authorization,
            @RequestPart("file") MultipartFile file,
            @RequestParam("domain") String domain,
            @RequestParam(value = "title", required = false) String title
    ) {
        adminService.requireAdmin(authorization);
        String admin = tokenService.requireAdmin(authorization);
        return ApiResponse.success(documentService.upload(file, domain, title, admin));
    }

    @PostMapping("/documents/{documentUid}/reindex")
    public ApiResponse<AiDtos.AdminAiDocumentView> reindex(
            @RequestHeader("Authorization") String authorization,
            @PathVariable String documentUid
    ) {
        adminService.requireAdmin(authorization);
        return ApiResponse.success(documentService.reindex(documentUid));
    }

    @PostMapping("/documents/import-business")
    public ApiResponse<Map<String, Integer>> importBusiness(@RequestHeader("Authorization") String authorization) {
        adminService.requireAdmin(authorization);
        String admin = tokenService.requireAdmin(authorization);
        int imported = documentService.importBusinessKnowledge(admin);
        return ApiResponse.success(Map.of("imported", imported));
    }

    @PostMapping("/publish")
    public ApiResponse<Void> publish(
            @RequestHeader("Authorization") String authorization,
            @RequestBody(required = false) AiDtos.AdminAiPublishRequest request
    ) {
        adminService.requireAdmin(authorization);
        String admin = tokenService.requireAdmin(authorization);
        documentService.publish(request == null ? "" : request.description(), admin);
        return ApiResponse.success();
    }

    @GetMapping("/publish")
    public ApiResponse<List<AiDtos.AdminAiPublishView>> publishList(@RequestHeader("Authorization") String authorization) {
        adminService.requireAdmin(authorization);
        return ApiResponse.success(assistantService.listPublishVersions());
    }

    @GetMapping("/logs")
    public ApiResponse<List<AiDtos.AdminAiLogView>> logs(@RequestHeader("Authorization") String authorization) {
        adminService.requireAdmin(authorization);
        return ApiResponse.success(assistantService.listAdminLogs());
    }

    @PostMapping("/debug/retrieve")
    public ApiResponse<AiDtos.AdminAiDebugResponse> debugRetrieve(
            @RequestHeader("Authorization") String authorization,
            @RequestBody @Valid AiDtos.AdminAiDebugRequest request
    ) {
        adminService.requireAdmin(authorization);
        return ApiResponse.success(assistantService.debugRetrieve(request));
    }

    @GetMapping("/evals")
    public ApiResponse<List<AiDtos.AdminAiEvalCaseView>> evals(@RequestHeader("Authorization") String authorization) {
        adminService.requireAdmin(authorization);
        return ApiResponse.success(assistantService.listEvalCases());
    }

    @PostMapping("/evals")
    public ApiResponse<Void> createEvalCase(
            @RequestHeader("Authorization") String authorization,
            @RequestBody @Valid AiDtos.AdminAiEvalCaseRequest request
    ) {
        adminService.requireAdmin(authorization);
        assistantService.createEvalCase(request);
        return ApiResponse.success();
    }

    @PostMapping("/rebuild")
    public ApiResponse<Map<String, Integer>> rebuild(@RequestHeader("Authorization") String authorization) {
        adminService.requireAdmin(authorization);
        return ApiResponse.success(documentService.rebuildAll());
    }

    @GetMapping("/stats")
    public ApiResponse<Map<String, Integer>> stats(@RequestHeader("Authorization") String authorization) {
        adminService.requireAdmin(authorization);
        return ApiResponse.success(documentService.stats());
    }
}
