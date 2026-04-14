package com.wolfbook.backend.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@Profile("!test")
public class JudgeSchemaInitializer implements ApplicationRunner {

    private final JdbcTemplate jdbcTemplate;

    public JudgeSchemaInitializer(@Qualifier("appJdbcTemplate") JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        ensureBoardColumns();
        createJudgeTables();
        createJudgeIndexes();
    }

    private void ensureBoardColumns() {
        createColumnIfMissing(
                "boards",
                "judge_support_level",
                "ALTER TABLE boards ADD COLUMN judge_support_level VARCHAR(20) NOT NULL DEFAULT 'manual_only' AFTER rule_type"
        );
        jdbcTemplate.execute("""
                UPDATE boards
                SET judge_support_level = CASE
                  WHEN judge_support_level IS NULL OR TRIM(judge_support_level) = '' THEN 'manual_only'
                  WHEN LOWER(TRIM(judge_support_level)) IN ('full', 'partial') THEN LOWER(TRIM(judge_support_level))
                  ELSE 'manual_only'
                END
                """);
    }

    private void createJudgeTables() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS judge_rooms (
                  room_id VARCHAR(64) PRIMARY KEY,
                  board_id INT NOT NULL,
                  board_name VARCHAR(100) NOT NULL,
                  player_count TINYINT NOT NULL,
                  judge_mode VARCHAR(20) NOT NULL DEFAULT 'observer',
                  judge_support_level VARCHAR(20) NOT NULL DEFAULT 'manual_only',
                  owner_user_id VARCHAR(100) NOT NULL,
                  owner_player_id VARCHAR(64),
                  owner_seat_no TINYINT,
                  room_status VARCHAR(20) NOT NULL DEFAULT 'lobby',
                  current_day TINYINT DEFAULT 1,
                  current_phase VARCHAR(30) DEFAULT 'lobby',
                  auto_judge_enabled TINYINT DEFAULT 0,
                  can_rollback TINYINT DEFAULT 1,
                  winner_camp VARCHAR(30),
                  latest_announcement VARCHAR(500),
                  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
                  update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
                )
                """);

        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS judge_room_players (
                  player_id VARCHAR(64) PRIMARY KEY,
                  room_id VARCHAR(64) NOT NULL,
                  user_id VARCHAR(100) NOT NULL,
                  nickname VARCHAR(50) NOT NULL,
                  avatar VARCHAR(500),
                  seat_no TINYINT,
                  is_room_owner TINYINT DEFAULT 0,
                  is_judge_observer TINYINT DEFAULT 0,
                  is_playing TINYINT DEFAULT 1,
                  ready TINYINT DEFAULT 0,
                  alive TINYINT DEFAULT 1,
                  role_id INT,
                  role_name VARCHAR(50),
                  faction VARCHAR(30),
                  death_day TINYINT,
                  death_phase VARCHAR(30),
                  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
                  update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                  UNIQUE KEY uk_judge_room_user (room_id, user_id),
                  UNIQUE KEY uk_judge_room_seat (room_id, seat_no)
                )
                """);

        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS judge_action_events (
                  event_id VARCHAR(64) PRIMARY KEY,
                  room_id VARCHAR(64) NOT NULL,
                  day_no TINYINT DEFAULT 1,
                  phase VARCHAR(30) NOT NULL,
                  actor_player_id VARCHAR(64),
                  target_player_ids_json JSON,
                  action_type VARCHAR(50) NOT NULL,
                  payload_json JSON,
                  result_payload_json JSON,
                  visibility VARCHAR(20) NOT NULL DEFAULT 'public',
                  create_time DATETIME DEFAULT CURRENT_TIMESTAMP
                )
                """);
    }

    private void createJudgeIndexes() {
        createIndexIfMissing("judge_rooms", "idx_judge_rooms_owner_update",
                "CREATE INDEX idx_judge_rooms_owner_update ON judge_rooms (owner_user_id, update_time)");
        createIndexIfMissing("judge_rooms", "idx_judge_rooms_status_update",
                "CREATE INDEX idx_judge_rooms_status_update ON judge_rooms (room_status, update_time)");
        createIndexIfMissing("judge_room_players", "idx_judge_room_players_room",
                "CREATE INDEX idx_judge_room_players_room ON judge_room_players (room_id, seat_no)");
        createIndexIfMissing("judge_room_players", "idx_judge_room_players_user",
                "CREATE INDEX idx_judge_room_players_user ON judge_room_players (user_id, update_time)");
        createIndexIfMissing("judge_action_events", "idx_judge_events_room_phase",
                "CREATE INDEX idx_judge_events_room_phase ON judge_action_events (room_id, day_no, phase, create_time)");
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
