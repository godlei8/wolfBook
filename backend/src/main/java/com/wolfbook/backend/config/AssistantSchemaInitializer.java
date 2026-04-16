package com.wolfbook.backend.config;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@Profile("!test")
public class AssistantSchemaInitializer implements ApplicationRunner {

    private final JdbcTemplate jdbcTemplate;

    public AssistantSchemaInitializer(@Qualifier("appJdbcTemplate") JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        createAssistantTables();
        upgradeAssistantTables();
        createAssistantIndexes();
    }

    private void createAssistantTables() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS assistant_config (
                  id INT PRIMARY KEY AUTO_INCREMENT,
                  base_config JSON,
                  prompt_config JSON,
                  retrieval_config JSON,
                  search_config JSON,
                  safety_config JSON,
                  ui_config JSON,
                  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
                  update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
                )
                """);

        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS assistant_documents (
                  id INT PRIMARY KEY AUTO_INCREMENT,
                  name VARCHAR(150) NOT NULL,
                  file_name VARCHAR(255),
                  source_type VARCHAR(30) NOT NULL,
                  source_key VARCHAR(100) NOT NULL,
                  source_id VARCHAR(100),
                  file_path VARCHAR(500),
                  summary VARCHAR(500),
                  content_text LONGTEXT,
                  content_hash VARCHAR(64),
                  document_version INT DEFAULT 1,
                  archived TINYINT DEFAULT 0,
                  ttl_days INT,
                  expires_at DATETIME,
                  evidence_updated_at DATETIME,
                  metadata_json JSON,
                  chunk_count INT DEFAULT 0,
                  processing_status VARCHAR(20) DEFAULT 'READY',
                  review_status VARCHAR(20) DEFAULT 'PENDING',
                  publish_version_id INT,
                  last_error VARCHAR(500),
                  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
                  update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                  UNIQUE KEY uk_assistant_documents_source_key (source_key)
                )
                """);

        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS assistant_knowledge_chunks (
                  id BIGINT PRIMARY KEY AUTO_INCREMENT,
                  chunk_uid VARCHAR(64) NOT NULL,
                  document_id INT NOT NULL,
                  publish_version_id INT,
                  source_type VARCHAR(30) NOT NULL,
                  source_key VARCHAR(100) NOT NULL,
                  subject_type VARCHAR(30),
                  subject_key VARCHAR(100),
                  subject_name VARCHAR(150),
                  section_title VARCHAR(200),
                  section_path VARCHAR(500),
                  chunk_type VARCHAR(40),
                  chunk_kind VARCHAR(40),
                  field_name VARCHAR(80),
                  content_text TEXT,
                  embedding_text TEXT,
                  content_hash VARCHAR(64),
                  chunk_version VARCHAR(40),
                  updated_at DATETIME,
                  source_url VARCHAR(500),
                  permission_tag VARCHAR(80),
                  parent_chunk_uid VARCHAR(64),
                  child_index INT,
                  child_count INT,
                  ordinal INT DEFAULT 0,
                  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
                  update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                  UNIQUE KEY uk_assistant_knowledge_chunks_uid (chunk_uid)
                )
                """);

        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS assistant_publish_versions (
                  id INT PRIMARY KEY AUTO_INCREMENT,
                  version_name VARCHAR(100) NOT NULL,
                  notes VARCHAR(500),
                  document_ids JSON,
                  is_current TINYINT DEFAULT 0,
                  published_by VARCHAR(100),
                  create_time DATETIME DEFAULT CURRENT_TIMESTAMP
                )
                """);

        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS assistant_sessions (
                  session_id VARCHAR(64) PRIMARY KEY,
                  openid VARCHAR(100) NOT NULL,
                  title VARCHAR(120),
                  scene VARCHAR(50),
                  page_context JSON,
                  retrieval_context JSON,
                  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
                  update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
                )
                """);

        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS assistant_messages (
                  id BIGINT PRIMARY KEY AUTO_INCREMENT,
                  session_id VARCHAR(64) NOT NULL,
                  role VARCHAR(20) NOT NULL,
                  content LONGTEXT NOT NULL,
                  answer_type VARCHAR(40),
                  citations JSON,
                  recommended_boards JSON,
                  suggested_questions JSON,
                  used_web_search TINYINT DEFAULT 0,
                  trace_id VARCHAR(64),
                  create_time DATETIME DEFAULT CURRENT_TIMESTAMP
                )
                """);

        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS assistant_query_logs (
                  id BIGINT PRIMARY KEY AUTO_INCREMENT,
                  openid VARCHAR(100),
                  session_id VARCHAR(64),
                  user_message VARCHAR(1000),
                  answer_type VARCHAR(40),
                  hit_sources JSON,
                  used_web_search TINYINT DEFAULT 0,
                  latency_ms BIGINT DEFAULT 0,
                  first_token_ms BIGINT DEFAULT 0,
                  embedding_ms BIGINT DEFAULT 0,
                  retrieval_ms BIGINT DEFAULT 0,
                  model_ms BIGINT DEFAULT 0,
                  web_search_ms BIGINT DEFAULT 0,
                  cache_hit TINYINT DEFAULT 0,
                  fallback_mode VARCHAR(40),
                  stream_mode VARCHAR(30),
                  success TINYINT DEFAULT 1,
                  failure_type VARCHAR(50),
                  trace_id VARCHAR(64),
                  create_time DATETIME DEFAULT CURRENT_TIMESTAMP
                )
                """);
    }

    private void upgradeAssistantTables() {
        addColumnIfMissing("assistant_sessions", "retrieval_context", "ALTER TABLE assistant_sessions ADD COLUMN retrieval_context JSON");
        addColumnIfMissing("assistant_query_logs", "first_token_ms", "ALTER TABLE assistant_query_logs ADD COLUMN first_token_ms BIGINT DEFAULT 0");
        addColumnIfMissing("assistant_query_logs", "embedding_ms", "ALTER TABLE assistant_query_logs ADD COLUMN embedding_ms BIGINT DEFAULT 0");
        addColumnIfMissing("assistant_query_logs", "retrieval_ms", "ALTER TABLE assistant_query_logs ADD COLUMN retrieval_ms BIGINT DEFAULT 0");
        addColumnIfMissing("assistant_query_logs", "model_ms", "ALTER TABLE assistant_query_logs ADD COLUMN model_ms BIGINT DEFAULT 0");
        addColumnIfMissing("assistant_query_logs", "web_search_ms", "ALTER TABLE assistant_query_logs ADD COLUMN web_search_ms BIGINT DEFAULT 0");
        addColumnIfMissing("assistant_query_logs", "cache_hit", "ALTER TABLE assistant_query_logs ADD COLUMN cache_hit TINYINT DEFAULT 0");
        addColumnIfMissing("assistant_query_logs", "fallback_mode", "ALTER TABLE assistant_query_logs ADD COLUMN fallback_mode VARCHAR(40)");
        addColumnIfMissing("assistant_query_logs", "stream_mode", "ALTER TABLE assistant_query_logs ADD COLUMN stream_mode VARCHAR(30)");
        addColumnIfMissing("assistant_query_logs", "retrieval_meta_json", "ALTER TABLE assistant_query_logs ADD COLUMN retrieval_meta_json JSON");
        addColumnIfMissing("assistant_knowledge_chunks", "section_path", "ALTER TABLE assistant_knowledge_chunks ADD COLUMN section_path VARCHAR(500)");
        addColumnIfMissing("assistant_knowledge_chunks", "chunk_type", "ALTER TABLE assistant_knowledge_chunks ADD COLUMN chunk_type VARCHAR(40)");
        addColumnIfMissing("assistant_knowledge_chunks", "chunk_version", "ALTER TABLE assistant_knowledge_chunks ADD COLUMN chunk_version VARCHAR(40)");
        addColumnIfMissing("assistant_knowledge_chunks", "updated_at", "ALTER TABLE assistant_knowledge_chunks ADD COLUMN updated_at DATETIME");
        addColumnIfMissing("assistant_knowledge_chunks", "source_url", "ALTER TABLE assistant_knowledge_chunks ADD COLUMN source_url VARCHAR(500)");
        addColumnIfMissing("assistant_knowledge_chunks", "permission_tag", "ALTER TABLE assistant_knowledge_chunks ADD COLUMN permission_tag VARCHAR(80)");
        addColumnIfMissing("assistant_knowledge_chunks", "parent_chunk_uid", "ALTER TABLE assistant_knowledge_chunks ADD COLUMN parent_chunk_uid VARCHAR(64)");
        addColumnIfMissing("assistant_knowledge_chunks", "child_index", "ALTER TABLE assistant_knowledge_chunks ADD COLUMN child_index INT");
        addColumnIfMissing("assistant_knowledge_chunks", "child_count", "ALTER TABLE assistant_knowledge_chunks ADD COLUMN child_count INT");
        addColumnIfMissing("assistant_documents", "content_hash", "ALTER TABLE assistant_documents ADD COLUMN content_hash VARCHAR(64)");
        addColumnIfMissing("assistant_documents", "document_version", "ALTER TABLE assistant_documents ADD COLUMN document_version INT DEFAULT 1");
        addColumnIfMissing("assistant_documents", "archived", "ALTER TABLE assistant_documents ADD COLUMN archived TINYINT DEFAULT 0");
        addColumnIfMissing("assistant_documents", "ttl_days", "ALTER TABLE assistant_documents ADD COLUMN ttl_days INT");
        addColumnIfMissing("assistant_documents", "expires_at", "ALTER TABLE assistant_documents ADD COLUMN expires_at DATETIME");
        addColumnIfMissing("assistant_documents", "evidence_updated_at", "ALTER TABLE assistant_documents ADD COLUMN evidence_updated_at DATETIME");
    }

    private void createAssistantIndexes() {
        createIndexIfMissing("assistant_documents", "idx_assistant_documents_publish",
                "CREATE INDEX idx_assistant_documents_publish ON assistant_documents (publish_version_id, review_status)");
        createIndexIfMissing("assistant_documents", "idx_assistant_documents_version",
                "CREATE INDEX idx_assistant_documents_version ON assistant_documents (source_id, document_version, archived)");
        createIndexIfMissing("assistant_knowledge_chunks", "idx_assistant_chunks_subject",
                "CREATE INDEX idx_assistant_chunks_subject ON assistant_knowledge_chunks (publish_version_id, subject_key, source_type)");
        createIndexIfMissing("assistant_knowledge_chunks", "idx_assistant_chunks_document",
                "CREATE INDEX idx_assistant_chunks_document ON assistant_knowledge_chunks (document_id, ordinal)");
        createIndexIfMissing("assistant_knowledge_chunks", "idx_assistant_chunks_type",
                "CREATE INDEX idx_assistant_chunks_type ON assistant_knowledge_chunks (publish_version_id, chunk_type, parent_chunk_uid)");
        createIndexIfMissing("assistant_sessions", "idx_assistant_sessions_openid",
                "CREATE INDEX idx_assistant_sessions_openid ON assistant_sessions (openid, update_time)");
        createIndexIfMissing("assistant_messages", "idx_assistant_messages_session",
                "CREATE INDEX idx_assistant_messages_session ON assistant_messages (session_id, create_time)");
        createIndexIfMissing("assistant_query_logs", "idx_assistant_logs_openid",
                "CREATE INDEX idx_assistant_logs_openid ON assistant_query_logs (openid, create_time)");
    }

    private void createIndexIfMissing(String tableName, String indexName, String ddl) {
        Integer count = jdbcTemplate.queryForObject(
                """
                        SELECT COUNT(1)
                        FROM information_schema.statistics
                        WHERE table_schema = DATABASE()
                          AND table_name = ?
                          AND index_name = ?
                        """,
                Integer.class,
                tableName,
                indexName
        );
        if (count == null || count == 0) {
            jdbcTemplate.execute(ddl);
        }
    }

    private void addColumnIfMissing(String tableName, String columnName, String ddl) {
        Integer count = jdbcTemplate.queryForObject(
                """
                        SELECT COUNT(1)
                        FROM information_schema.columns
                        WHERE table_schema = DATABASE()
                          AND table_name = ?
                          AND column_name = ?
                        """,
                Integer.class,
                tableName,
                columnName
        );
        if (count == null || count == 0) {
            jdbcTemplate.execute(ddl);
        }
    }
}
