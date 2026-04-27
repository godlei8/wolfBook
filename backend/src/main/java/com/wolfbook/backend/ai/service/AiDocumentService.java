package com.wolfbook.backend.ai.service;

import com.wolfbook.backend.ai.dto.AiDtos;
import com.wolfbook.backend.ai.model.AiDocumentRecord;
import com.wolfbook.backend.ai.model.AiKbChunkRecord;
import com.wolfbook.backend.ai.model.AiKnowledgeChunk;
import com.wolfbook.backend.ai.model.AiServiceException;
import com.wolfbook.backend.ai.store.AiKnowledgeStore;
import com.wolfbook.backend.common.ApiException;
import com.wolfbook.backend.domain.Board;
import com.wolfbook.backend.dto.WolfbookDtos;
import com.wolfbook.backend.service.BoardService;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

@Service
public class AiDocumentService {

    private final AiKnowledgeStore store;
    private final AiKnowledgeChunker chunker;
    private final AiDocumentTextExtractor textExtractor;
    private final MiniMaxGateway miniMaxGateway;
    private final BoardService boardService;

    public AiDocumentService(
            AiKnowledgeStore store,
            AiKnowledgeChunker chunker,
            AiDocumentTextExtractor textExtractor,
            MiniMaxGateway miniMaxGateway,
            BoardService boardService
    ) {
        this.store = store;
        this.chunker = chunker;
        this.textExtractor = textExtractor;
        this.miniMaxGateway = miniMaxGateway;
        this.boardService = boardService;
    }

    public boolean ready() {
        return store.ready();
    }

    public List<AiDtos.AdminAiDocumentView> listDocuments() {
        if (!store.ready()) {
            return List.of();
        }
        return store.listDocuments().stream().map(this::toDocumentView).toList();
    }

    public AiDtos.AdminAiDocumentView upload(MultipartFile file, String domain, String title, String username) {
        requireStore();
        String content = textExtractor.extract(file);
        String resolvedTitle = title == null || title.isBlank()
                ? stripExtension(file.getOriginalFilename())
                : title.trim();
        AiDocumentRecord document = store.createDocument(normalizeDomain(domain), resolvedTitle, "UPLOAD", file.getOriginalFilename(), content, username);
        reindex(document.documentUid());
        return toDocumentView(store.getDocument(document.documentUid()).orElse(document));
    }

    public AiDtos.AdminAiDocumentView reindex(String documentUid) {
        requireStore();
        AiDocumentRecord document = store.getDocument(documentUid)
                .orElseThrow(() -> new ApiException(4004, "知识文档不存在"));
        List<AiKnowledgeChunk> chunks = chunker.chunk(document.domain(), document.title(), document.content());
        List<AiKbChunkRecord> records = chunks.stream()
                .map(chunk -> new AiKbChunkRecord(
                        chunk.chunkUid(),
                        document.documentUid(),
                        chunk.domain(),
                        chunk.title(),
                        chunk.sectionPath(),
                        chunk.subject(),
                        chunk.content(),
                        chunk.ordinal(),
                        0,
                        "chunk"
                ))
                .toList();
        List<float[]> embeddings = null;
        if (miniMaxGateway.embeddingReady()) {
            try {
                embeddings = miniMaxGateway.embedForDb(records.stream().map(AiKbChunkRecord::content).toList());
            } catch (AiServiceException ignored) {
                embeddings = null;
            }
        }
        store.replaceChunks(document.documentUid(), records, embeddings);
        return toDocumentView(store.getDocument(document.documentUid()).orElse(document));
    }

    public Map<String, Integer> rebuildAll() {
        requireStore();
        for (AiDocumentRecord document : store.listDocuments()) {
            reindex(document.documentUid());
        }
        return store.rebuildStats();
    }

    public int importBusinessKnowledge(String username) {
        requireStore();
        int count = 0;
        for (WolfbookDtos.RoleListItemView role : boardService.listRoles(null)) {
            WolfbookDtos.RoleDetailView detail = boardService.getRoleDetail(role.id());
            AiDocumentRecord document = store.createDocument(
                    "roles",
                    detail.name(),
                    "BUSINESS_ROLE",
                    String.valueOf(detail.id()),
                    renderRoleMarkdown(detail),
                    username
            );
            reindex(document.documentUid());
            count++;
        }
        for (Board board : boardService.listAllBoards()) {
            AiDocumentRecord document = store.createDocument(
                    "boards",
                    board.name(),
                    "BUSINESS_BOARD",
                    String.valueOf(board.id()),
                    renderBoardMarkdown(board),
                    username
            );
            reindex(document.documentUid());
            count++;
        }
        return count;
    }

    public void publish(String description, String username) {
        requireStore();
        String versionKey = "ai-v" + System.currentTimeMillis();
        store.publish(versionKey, description, username);
    }

    public Map<String, Integer> stats() {
        if (!store.ready()) {
            return Map.of("chunks", 0, "vectors", 0, "missingVectors", 0, "orphanVectors", 0);
        }
        return store.rebuildStats();
    }

    private String renderRoleMarkdown(WolfbookDtos.RoleDetailView role) {
        StringBuilder builder = new StringBuilder();
        builder.append("## 角色：").append(role.name()).append('\n');
        builder.append("### 基础信息\n");
        builder.append("- 阵营：").append(nullToEmpty(role.faction())).append('\n');
        builder.append("- 类型：").append(nullToEmpty(role.roleType())).append('\n');
        builder.append("- 别名：").append(nullToEmpty(role.alias())).append('\n');
        builder.append("### 技能说明\n").append(nullToEmpty(role.skill())).append('\n');
        builder.append("### 背景与玩法\n").append(nullToEmpty(role.background())).append('\n');
        appendFaqs(builder, role.faqs());
        return builder.toString();
    }

    private String renderBoardMarkdown(Board board) {
        StringBuilder builder = new StringBuilder();
        builder.append("## 板子：").append(board.name()).append('\n');
        builder.append("### 基础配置\n");
        builder.append("- 人数：").append(board.playerCount()).append('\n');
        builder.append("- 难度：").append(nullToEmpty(board.difficulty())).append('\n');
        builder.append("- 阵容：").append(nullToEmpty(board.briefConfig())).append('\n');
        builder.append("### 规则说明\n").append(nullToEmpty(board.cardDescription())).append('\n');
        builder.append("### 胜利条件\n").append(nullToEmpty(board.winCondition())).append('\n');
        if (board.specialRules() != null && !board.specialRules().isEmpty()) {
            builder.append("### 特殊规则\n");
            board.specialRules().forEach(rule -> builder.append("- ").append(rule).append('\n'));
        }
        if (board.tips() != null && !board.tips().isEmpty()) {
            builder.append("### 玩法提示\n");
            board.tips().forEach(tip -> builder.append("- ").append(tip).append('\n'));
        }
        List<WolfbookDtos.FaqInput> faqs = board.faqs() == null
                ? List.of()
                : board.faqs().stream().map(faq -> new WolfbookDtos.FaqInput(faq.question(), faq.answer())).toList();
        appendFaqs(builder, faqs);
        return builder.toString();
    }

    private void appendFaqs(StringBuilder builder, List<WolfbookDtos.FaqInput> faqs) {
        if (faqs == null || faqs.isEmpty()) {
            return;
        }
        builder.append("### FAQ\n");
        for (WolfbookDtos.FaqInput faq : faqs) {
            builder.append("问：").append(faq.question()).append('\n');
            builder.append("答：").append(faq.answer()).append('\n');
        }
    }

    private AiDtos.AdminAiDocumentView toDocumentView(AiDocumentRecord record) {
        return new AiDtos.AdminAiDocumentView(
                record.id(),
                record.documentUid(),
                record.domain(),
                record.title(),
                record.sourceType(),
                record.reviewStatus(),
                record.parseStatus(),
                record.summary(),
                record.chunkCount(),
                record.active(),
                record.createdAt(),
                record.updatedAt()
        );
    }

    private void requireStore() {
        if (!store.ready()) {
            throw new ApiException(5001, "AI PostgreSQL/pgvector 未配置，无法操作知识库");
        }
    }

    private String normalizeDomain(String domain) {
        if (domain == null || domain.isBlank()) {
            return "terms";
        }
        String value = domain.trim().toLowerCase();
        return switch (value) {
            case "roles", "boards", "terms", "news" -> value;
            default -> "terms";
        };
    }

    private String stripExtension(String filename) {
        if (filename == null || filename.isBlank()) {
            return "未命名知识文档";
        }
        int index = filename.lastIndexOf('.');
        return index > 0 ? filename.substring(0, index) : filename;
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
