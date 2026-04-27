package com.wolfbook.backend.ai.store;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wolfbook.backend.ai.model.AiDocumentRecord;
import com.wolfbook.backend.ai.model.AiKbChunkRecord;
import com.wolfbook.backend.ai.model.AiQaLogRecord;
import com.wolfbook.backend.ai.model.AiRetrievalCandidate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
public class AiKnowledgeStore {

    private static final TypeReference<List<Map<String, Object>>> SOURCE_LIST_TYPE = new TypeReference<>() {
    };

    private final ObjectProvider<JdbcTemplate> jdbcTemplateProvider;
    private final ObjectMapper objectMapper;

    public AiKnowledgeStore(@Qualifier("aiJdbcTemplate") ObjectProvider<JdbcTemplate> jdbcTemplateProvider, ObjectMapper objectMapper) {
        this.jdbcTemplateProvider = jdbcTemplateProvider;
        this.objectMapper = objectMapper;
    }

    public boolean ready() {
        return jdbcTemplateProvider.getIfAvailable() != null;
    }

    public List<AiDocumentRecord> listDocuments() {
        JdbcTemplate jdbcTemplate = requireJdbcTemplate();
        return jdbcTemplate.query("""
                SELECT id, document_uid, domain, title, source_type, source_ref, review_status, parse_status,
                       content, content_hash, summary, chunk_count, active, created_at, updated_at
                FROM ai_kb_document
                ORDER BY updated_at DESC, id DESC
                """, documentMapper());
    }

    public AiDocumentRecord createDocument(String domain, String title, String sourceType, String sourceRef, String content, String username) {
        JdbcTemplate jdbcTemplate = requireJdbcTemplate();
        String uid = "doc-" + sha256Hex(domain + "|" + title + "|" + content).substring(0, 24);
        String hash = sha256Hex(content);
        jdbcTemplate.update("""
                INSERT INTO ai_kb_document(document_uid, domain, title, source_type, source_ref, review_status, parse_status,
                                           content, content_hash, summary, created_by)
                VALUES (?, ?, ?, ?, ?, 'APPROVED', 'PENDING', ?, ?, ?, ?)
                ON CONFLICT(document_uid) DO UPDATE SET
                    domain = EXCLUDED.domain,
                    title = EXCLUDED.title,
                    source_type = EXCLUDED.source_type,
                    source_ref = EXCLUDED.source_ref,
                    content = EXCLUDED.content,
                    content_hash = EXCLUDED.content_hash,
                    summary = EXCLUDED.summary,
                    parse_status = 'PENDING',
                    updated_at = NOW()
                """, uid, domain, title, sourceType, sourceRef, content, hash, summarize(content), username);
        return getDocument(uid).orElseThrow();
    }

    public Optional<AiDocumentRecord> getDocument(String documentUid) {
        JdbcTemplate jdbcTemplate = requireJdbcTemplate();
        List<AiDocumentRecord> records = jdbcTemplate.query("""
                SELECT id, document_uid, domain, title, source_type, source_ref, review_status, parse_status,
                       content, content_hash, summary, chunk_count, active, created_at, updated_at
                FROM ai_kb_document
                WHERE document_uid = ?
                """, documentMapper(), documentUid);
        return records.stream().findFirst();
    }

    public void replaceChunks(String documentUid, List<AiKbChunkRecord> chunks, List<float[]> embeddings) {
        JdbcTemplate jdbcTemplate = requireJdbcTemplate();
        jdbcTemplate.update("DELETE FROM vector_store WHERE metadata ->> 'documentUid' = ?", documentUid);
        jdbcTemplate.update("DELETE FROM ai_kb_chunk WHERE document_uid = ?", documentUid);
        for (int index = 0; index < chunks.size(); index++) {
            AiKbChunkRecord chunk = chunks.get(index);
            jdbcTemplate.update("""
                    INSERT INTO ai_kb_chunk(chunk_uid, document_uid, domain, title, section_path, subject_key,
                                            content, ordinal, content_hash, search_vector)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, to_tsvector('simple', ?))
                    """,
                    chunk.chunkUid(), documentUid, chunk.domain(), chunk.title(), chunk.sectionPath(), chunk.subject(),
                    chunk.content(), chunk.ordinal(), sha256Hex(chunk.content()), chunk.content());
            if (embeddings != null && embeddings.size() > index && embeddings.get(index) != null) {
                insertVector(jdbcTemplate, documentUid, chunk, embeddings.get(index));
            }
            upsertAlias(chunk.subject(), chunk.domain(), "chunk");
        }
        jdbcTemplate.update("""
                UPDATE ai_kb_document
                SET chunk_count = ?, parse_status = 'COMPLETED', updated_at = NOW()
                WHERE document_uid = ?
                """, chunks.size(), documentUid);
    }

    public void upsertAlias(String subject, String domain, String source) {
        if (subject == null || subject.isBlank()) {
            return;
        }
        JdbcTemplate jdbcTemplate = requireJdbcTemplate();
        jdbcTemplate.update("""
                INSERT INTO ai_kb_alias(alias, subject_key, domain, source)
                VALUES (?, ?, ?, ?)
                ON CONFLICT(alias, subject_key, domain) DO UPDATE SET active = TRUE
                """, subject, subject, domain, source);
    }

    public List<String> listSubjects() {
        JdbcTemplate jdbcTemplate = requireJdbcTemplate();
        return jdbcTemplate.queryForList("""
                SELECT subject_key FROM ai_kb_alias WHERE active = TRUE
                UNION
                SELECT DISTINCT subject_key FROM ai_kb_chunk WHERE active = TRUE AND subject_key IS NOT NULL
                """, String.class);
    }

    public List<AiRetrievalCandidate> searchText(String query, String subject, int limit) {
        JdbcTemplate jdbcTemplate = requireJdbcTemplate();
        String likeQuery = "%" + query + "%";
        if (subject == null || subject.isBlank()) {
            return jdbcTemplate.query("""
                    SELECT chunk_uid, subject_key, content,
                           (similarity(content, ?) + CASE WHEN content ILIKE ? THEN 0.4 ELSE 0 END) AS score
                    FROM ai_kb_chunk
                    WHERE active = TRUE AND (content ILIKE ? OR title ILIKE ? OR section_path ILIKE ?)
                    ORDER BY score DESC, ordinal ASC
                    LIMIT ?
                    """, retrievalCandidateMapper("fts"), query, likeQuery, likeQuery, likeQuery, likeQuery, limit);
        }
        return jdbcTemplate.query("""
                SELECT chunk_uid, subject_key, content,
                       (similarity(content, ?) + CASE WHEN subject_key = ? THEN 1 ELSE 0 END + CASE WHEN content ILIKE ? THEN 0.4 ELSE 0 END) AS score
                FROM ai_kb_chunk
                WHERE active = TRUE
                  AND (subject_key = ? OR content ILIKE ? OR title ILIKE ? OR section_path ILIKE ?)
                ORDER BY score DESC, ordinal ASC
                LIMIT ?
                """, retrievalCandidateMapper("fts"), query, subject, likeQuery, subject, likeQuery, likeQuery, likeQuery, limit);
    }

    public List<AiRetrievalCandidate> searchVector(float[] embedding, String subject, int limit) {
        if (embedding == null) {
            return List.of();
        }
        JdbcTemplate jdbcTemplate = requireJdbcTemplate();
        String vector = vectorLiteral(embedding);
        if (subject == null || subject.isBlank()) {
            return jdbcTemplate.query("""
                    SELECT c.chunk_uid, c.subject_key, c.content, (1 - (v.embedding <=> ?::vector)) AS score
                    FROM vector_store v
                    JOIN ai_kb_chunk c ON v.metadata ->> 'chunkUid' = c.chunk_uid
                    WHERE c.active = TRUE
                    ORDER BY v.embedding <=> ?::vector
                    LIMIT ?
                    """, retrievalCandidateMapper("vector"), vector, vector, limit);
        }
        return jdbcTemplate.query("""
                SELECT c.chunk_uid, c.subject_key, c.content, (1 - (v.embedding <=> ?::vector)) AS score
                FROM vector_store v
                JOIN ai_kb_chunk c ON v.metadata ->> 'chunkUid' = c.chunk_uid
                WHERE c.active = TRUE AND (c.subject_key = ? OR c.content ILIKE ?)
                ORDER BY v.embedding <=> ?::vector
                LIMIT ?
                """, retrievalCandidateMapper("vector"), vector, subject, "%" + subject + "%", vector, limit);
    }

    public void publish(String versionKey, String description, String username) {
        JdbcTemplate jdbcTemplate = requireJdbcTemplate();
        Integer documentCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ai_kb_document WHERE active = TRUE AND review_status = 'APPROVED'", Integer.class);
        Integer chunkCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ai_kb_chunk WHERE active = TRUE", Integer.class);
        jdbcTemplate.update("UPDATE ai_kb_version SET active = FALSE");
        jdbcTemplate.update("""
                INSERT INTO ai_kb_version(version_key, status, description, document_count, chunk_count, active, created_by, activated_at)
                VALUES (?, 'ONLINE', ?, ?, ?, TRUE, ?, NOW())
                ON CONFLICT(version_key) DO UPDATE SET
                    status = 'ONLINE',
                    description = EXCLUDED.description,
                    document_count = EXCLUDED.document_count,
                    chunk_count = EXCLUDED.chunk_count,
                    active = TRUE,
                    activated_at = NOW()
                """, versionKey, description, defaultInt(documentCount), defaultInt(chunkCount), username);
        jdbcTemplate.update("UPDATE ai_kb_chunk SET version_key = ? WHERE active = TRUE", versionKey);
    }

    public Map<String, Integer> rebuildStats() {
        JdbcTemplate jdbcTemplate = requireJdbcTemplate();
        Integer chunks = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM ai_kb_chunk WHERE active = TRUE", Integer.class);
        Integer vectors = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM vector_store", Integer.class);
        Integer missing = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM ai_kb_chunk c
                LEFT JOIN vector_store v ON v.metadata ->> 'chunkUid' = c.chunk_uid
                WHERE c.active = TRUE AND v.id IS NULL
                """, Integer.class);
        Integer orphan = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM vector_store v
                LEFT JOIN ai_kb_chunk c ON v.metadata ->> 'chunkUid' = c.chunk_uid
                WHERE c.chunk_uid IS NULL
                """, Integer.class);
        return Map.of(
                "chunks", defaultInt(chunks),
                "vectors", defaultInt(vectors),
                "missingVectors", defaultInt(missing),
                "orphanVectors", defaultInt(orphan)
        );
    }

    public List<Map<String, Object>> listPublishVersions() {
        JdbcTemplate jdbcTemplate = requireJdbcTemplate();
        return jdbcTemplate.queryForList("""
                SELECT version_key, status, active, document_count, chunk_count, created_at, activated_at
                FROM ai_kb_version
                ORDER BY created_at DESC
                LIMIT 100
                """);
    }

    public void saveMessage(String sessionId, String openid, String role, String content, String messageId,
                            String traceId, String answerType, List<Map<String, Object>> sources, String confirmedEntity) {
        JdbcTemplate jdbcTemplate = requireJdbcTemplate();
        jdbcTemplate.update("""
                INSERT INTO ai_qa_session(session_id, openid, title, confirmed_entity)
                VALUES (?, ?, ?, ?)
                ON CONFLICT(session_id) DO UPDATE SET confirmed_entity = EXCLUDED.confirmed_entity, update_time = NOW()
                """, sessionId, openid, summarize(content), confirmedEntity);
        jdbcTemplate.update("""
                INSERT INTO ai_qa_message(message_id, session_id, role, content, answer_type, trace_id, sources)
                VALUES (?, ?, ?, ?, ?, ?, ?::jsonb)
                ON CONFLICT(message_id) DO NOTHING
                """, messageId, sessionId, role, content, answerType, traceId, toJson(sources));
    }

    public void saveLog(String traceId, String sessionId, String question, String answer, String answerType,
                        String subject, String retrievalMode, int hitCount, boolean webUsed, String failureReason, long latencyMs) {
        JdbcTemplate jdbcTemplate = requireJdbcTemplate();
        jdbcTemplate.update("""
                INSERT INTO ai_qa_log(trace_id, session_id, question, answer, answer_type, subject_key, retrieval_mode,
                                      hit_count, web_used, failure_reason, latency_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(trace_id) DO NOTHING
                """, traceId, sessionId, question, answer, answerType, subject, retrievalMode, hitCount, webUsed, failureReason, latencyMs);
    }

    public List<AiQaLogRecord> listLogs(int limit) {
        JdbcTemplate jdbcTemplate = requireJdbcTemplate();
        return jdbcTemplate.query("""
                SELECT trace_id, session_id, question, answer_type, subject_key, retrieval_mode,
                       hit_count, web_used, failure_reason, latency_ms, created_at
                FROM ai_qa_log
                ORDER BY created_at DESC
                LIMIT ?
                """, logMapper(), Math.max(limit, 1));
    }

    public List<Map<String, Object>> listSessions(String openid) {
        JdbcTemplate jdbcTemplate = requireJdbcTemplate();
        return jdbcTemplate.queryForList("""
                SELECT session_id, title, confirmed_entity, create_time, update_time
                FROM ai_qa_session
                WHERE openid = ?
                ORDER BY update_time DESC
                LIMIT 50
                """, openid);
    }

    public List<Map<String, Object>> listMessages(String sessionId) {
        JdbcTemplate jdbcTemplate = requireJdbcTemplate();
        return jdbcTemplate.queryForList("""
                SELECT message_id, role, content, answer_type, trace_id, sources, create_time
                FROM ai_qa_message
                WHERE session_id = ?
                ORDER BY id ASC
                """, sessionId);
    }

    public Optional<String> confirmedEntity(String sessionId) {
        JdbcTemplate jdbcTemplate = requireJdbcTemplate();
        List<String> values = jdbcTemplate.queryForList(
                "SELECT confirmed_entity FROM ai_qa_session WHERE session_id = ? AND confirmed_entity IS NOT NULL",
                String.class,
                sessionId
        );
        return values.stream().findFirst();
    }

    public void saveConfig(Map<String, String> values) {
        JdbcTemplate jdbcTemplate = requireJdbcTemplate();
        values.forEach((key, value) -> jdbcTemplate.update("""
                INSERT INTO ai_runtime_config(config_key, config_value)
                VALUES (?, ?)
                ON CONFLICT(config_key) DO UPDATE SET config_value = EXCLUDED.config_value, updated_at = NOW()
                """, key, value));
    }

    public Map<String, String> loadConfig() {
        JdbcTemplate jdbcTemplate = requireJdbcTemplate();
        return jdbcTemplate.query("""
                SELECT config_key, config_value
                FROM ai_runtime_config
                """, rs -> {
            Map<String, String> values = new java.util.LinkedHashMap<>();
            while (rs.next()) {
                values.put(rs.getString("config_key"), rs.getString("config_value"));
            }
            return values;
        });
    }

    public List<Map<String, Object>> listEvalCases() {
        JdbcTemplate jdbcTemplate = requireJdbcTemplate();
        return jdbcTemplate.queryForList("""
                SELECT id, question, expected_subject, expected_keywords, category, enabled, created_at
                FROM ai_eval_case
                ORDER BY id DESC
                LIMIT 500
                """);
    }

    public void insertEvalCase(String question, String expectedSubject, String expectedKeywords, String category) {
        JdbcTemplate jdbcTemplate = requireJdbcTemplate();
        jdbcTemplate.update("""
                INSERT INTO ai_eval_case(question, expected_subject, expected_keywords, category)
                VALUES (?, ?, ?, ?)
                """, question, expectedSubject, expectedKeywords, category);
    }

    private JdbcTemplate requireJdbcTemplate() {
        JdbcTemplate jdbcTemplate = jdbcTemplateProvider.getIfAvailable();
        if (jdbcTemplate == null) {
            throw new IllegalStateException("AI PostgreSQL is not configured");
        }
        return jdbcTemplate;
    }

    private void insertVector(JdbcTemplate jdbcTemplate, String documentUid, AiKbChunkRecord chunk, float[] embedding) {
        UUID id = UUID.nameUUIDFromBytes(chunk.chunkUid().getBytes(StandardCharsets.UTF_8));
        Map<String, Object> metadata = Map.of(
                "documentUid", documentUid,
                "chunkUid", chunk.chunkUid(),
                "domain", chunk.domain(),
                "subject", chunk.subject() == null ? "" : chunk.subject()
        );
        jdbcTemplate.update("""
                INSERT INTO vector_store(id, content, metadata, embedding)
                VALUES (?::uuid, ?, ?::jsonb, ?::vector)
                ON CONFLICT(id) DO UPDATE SET content = EXCLUDED.content, metadata = EXCLUDED.metadata, embedding = EXCLUDED.embedding
                """, id.toString(), chunk.content(), toJson(metadata), vectorLiteral(embedding));
    }

    private RowMapper<AiDocumentRecord> documentMapper() {
        return (rs, rowNum) -> new AiDocumentRecord(
                rs.getLong("id"),
                rs.getString("document_uid"),
                rs.getString("domain"),
                rs.getString("title"),
                rs.getString("source_type"),
                rs.getString("source_ref"),
                rs.getString("review_status"),
                rs.getString("parse_status"),
                rs.getString("content"),
                rs.getString("content_hash"),
                rs.getString("summary"),
                rs.getInt("chunk_count"),
                rs.getBoolean("active"),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class)
        );
    }

    private RowMapper<AiRetrievalCandidate> retrievalCandidateMapper(String source) {
        return (rs, rowNum) -> new AiRetrievalCandidate(
                rs.getString("chunk_uid"),
                rs.getString("subject_key"),
                rs.getString("content"),
                rs.getDouble("score"),
                source
        );
    }

    private RowMapper<AiQaLogRecord> logMapper() {
        return (rs, rowNum) -> new AiQaLogRecord(
                rs.getString("trace_id"),
                rs.getString("session_id"),
                rs.getString("question"),
                rs.getString("answer_type"),
                rs.getString("subject_key"),
                rs.getString("retrieval_mode"),
                rs.getInt("hit_count"),
                rs.getBoolean("web_used"),
                rs.getString("failure_reason"),
                rs.getObject("latency_ms", Long.class),
                rs.getObject("created_at", OffsetDateTime.class)
        );
    }

    private String summarize(String content) {
        if (content == null) {
            return "";
        }
        String normalized = content.replaceAll("\\s+", " ").trim();
        return normalized.length() <= 120 ? normalized : normalized.substring(0, 120);
    }

    private String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 not available", exception);
        }
    }

    private String vectorLiteral(float[] vector) {
        StringBuilder builder = new StringBuilder("[");
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                builder.append(',');
            }
            builder.append(vector[i]);
        }
        return builder.append(']').toString();
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize AI payload", exception);
        }
    }

    public List<Map<String, Object>> readSourceList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, SOURCE_LIST_TYPE);
        } catch (JsonProcessingException exception) {
            return List.of();
        }
    }

    private int defaultInt(Integer value) {
        return value == null ? 0 : value;
    }
}
