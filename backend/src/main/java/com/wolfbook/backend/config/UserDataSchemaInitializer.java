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
        ensureUserDataColumns();
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
                  version INT DEFAULT 2,
                  openid VARCHAR(100) NOT NULL,
                  board_mode VARCHAR(20) NOT NULL DEFAULT 'library',
                  board_id INT,
                  board_name VARCHAR(100) NOT NULL,
                  player_count TINYINT NOT NULL,
                  status VARCHAR(20) NOT NULL DEFAULT 'active',
                  current_day TINYINT DEFAULT 1,
                  current_phase VARCHAR(30) DEFAULT 'day_speech',
                  result_camp VARCHAR(20) DEFAULT '',
                  sheriff_seat TINYINT,
                  players_json JSON,
                  summary_json JSON,
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
                  scene VARCHAR(20) DEFAULT '',
                  day_no TINYINT DEFAULT 1,
                  phase VARCHAR(30) DEFAULT '',
                  actor_seats_json JSON,
                  target_seats_json JSON,
                  content TEXT NOT NULL,
                  player VARCHAR(255),
                  payload_json JSON,
                  tags_json JSON,
                  editable TINYINT DEFAULT 1,
                  create_time DATETIME DEFAULT CURRENT_TIMESTAMP
                  ,
                  update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
                )
                """);
    }

    private void ensureUserDataColumns() {
        createColumnIfMissing("user_note_sessions", "version",
                "ALTER TABLE user_note_sessions ADD COLUMN version INT DEFAULT 2");
        createColumnIfMissing("user_note_sessions", "status",
                "ALTER TABLE user_note_sessions ADD COLUMN status VARCHAR(20) NOT NULL DEFAULT 'active'");
        createColumnIfMissing("user_note_sessions", "current_day",
                "ALTER TABLE user_note_sessions ADD COLUMN current_day TINYINT DEFAULT 1");
        createColumnIfMissing("user_note_sessions", "current_phase",
                "ALTER TABLE user_note_sessions ADD COLUMN current_phase VARCHAR(30) DEFAULT 'day_speech'");
        createColumnIfMissing("user_note_sessions", "result_camp",
                "ALTER TABLE user_note_sessions ADD COLUMN result_camp VARCHAR(20) DEFAULT ''");
        createColumnIfMissing("user_note_sessions", "sheriff_seat",
                "ALTER TABLE user_note_sessions ADD COLUMN sheriff_seat TINYINT");
        createColumnIfMissing("user_note_sessions", "players_json",
                "ALTER TABLE user_note_sessions ADD COLUMN players_json JSON");
        createColumnIfMissing("user_note_sessions", "summary_json",
                "ALTER TABLE user_note_sessions ADD COLUMN summary_json JSON");

        createColumnIfMissing("user_note_records", "scene",
                "ALTER TABLE user_note_records ADD COLUMN scene VARCHAR(20) DEFAULT ''");
        createColumnIfMissing("user_note_records", "phase",
                "ALTER TABLE user_note_records ADD COLUMN phase VARCHAR(30) DEFAULT ''");
        createColumnIfMissing("user_note_records", "actor_seats_json",
                "ALTER TABLE user_note_records ADD COLUMN actor_seats_json JSON");
        createColumnIfMissing("user_note_records", "target_seats_json",
                "ALTER TABLE user_note_records ADD COLUMN target_seats_json JSON");
        createColumnIfMissing("user_note_records", "payload_json",
                "ALTER TABLE user_note_records ADD COLUMN payload_json JSON");
        createColumnIfMissing("user_note_records", "tags_json",
                "ALTER TABLE user_note_records ADD COLUMN tags_json JSON");
        createColumnIfMissing("user_note_records", "editable",
                "ALTER TABLE user_note_records ADD COLUMN editable TINYINT DEFAULT 1");
        createColumnIfMissing("user_note_records", "update_time",
                "ALTER TABLE user_note_records ADD COLUMN update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP");
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

    private void createColumnIfMissing(String tableName, String columnName, String ddl) {
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
