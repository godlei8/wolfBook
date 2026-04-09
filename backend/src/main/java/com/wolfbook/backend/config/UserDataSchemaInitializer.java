package com.wolfbook.backend.config;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@Profile("!test")
public class UserDataSchemaInitializer implements ApplicationRunner {

    private final JdbcTemplate jdbcTemplate;

    public UserDataSchemaInitializer(@Qualifier("appJdbcTemplate") JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        createUserDataTables();
        createUserDataIndexes();
    }

    private void createUserDataTables() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS user_favorite_boards (
                  id BIGINT PRIMARY KEY AUTO_INCREMENT,
                  openid VARCHAR(100) NOT NULL,
                  board_id INT NOT NULL,
                  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
                  UNIQUE KEY uk_user_favorite_board (openid, board_id)
                )
                """);

        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS user_note_sessions (
                  session_id VARCHAR(64) PRIMARY KEY,
                  openid VARCHAR(100) NOT NULL,
                  board_mode VARCHAR(20) NOT NULL DEFAULT 'library',
                  board_id INT,
                  board_name VARCHAR(100) NOT NULL,
                  player_count TINYINT NOT NULL,
                  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
                  update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
                )
                """);

        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS user_note_records (
                  record_id VARCHAR(64) PRIMARY KEY,
                  session_id VARCHAR(64) NOT NULL,
                  openid VARCHAR(100) NOT NULL,
                  record_type VARCHAR(20) NOT NULL,
                  day_no TINYINT DEFAULT 1,
                  content TEXT NOT NULL,
                  player VARCHAR(255),
                  create_time DATETIME DEFAULT CURRENT_TIMESTAMP
                )
                """);
    }

    private void createUserDataIndexes() {
        createIndexIfMissing("user_favorite_boards", "idx_user_favorite_openid",
                "CREATE INDEX idx_user_favorite_openid ON user_favorite_boards (openid, create_time)");
        createIndexIfMissing("user_note_sessions", "idx_user_note_sessions_openid",
                "CREATE INDEX idx_user_note_sessions_openid ON user_note_sessions (openid, update_time)");
        createIndexIfMissing("user_note_records", "idx_user_note_records_session",
                "CREATE INDEX idx_user_note_records_session ON user_note_records (session_id, create_time)");
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
