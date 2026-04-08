package com.wolfbook.backend.config;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@Profile("!test")
public class AssistantSchemaInitializer implements ApplicationRunner {

    private final JdbcTemplate jdbcTemplate;

    public AssistantSchemaInitializer(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        createAssistantTables();
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
                  success TINYINT DEFAULT 1,
                  failure_type VARCHAR(50),
                  trace_id VARCHAR(64),
                  create_time DATETIME DEFAULT CURRENT_TIMESTAMP
                )
                """);
    }

    private void createAssistantIndexes() {
        createIndexIfMissing("assistant_documents", "idx_assistant_documents_publish",
                "CREATE INDEX idx_assistant_documents_publish ON assistant_documents (publish_version_id, review_status)");
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
}
