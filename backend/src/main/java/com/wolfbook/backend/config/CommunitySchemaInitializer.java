package com.wolfbook.backend.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@Profile("!test")
public class CommunitySchemaInitializer implements ApplicationRunner {

    private final JdbcTemplate jdbcTemplate;

    public CommunitySchemaInitializer(@Qualifier("appJdbcTemplate") JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        createCommunityTables();
        ensureCommunityColumns();
        normalizeLegacyStatuses();
        backfillLegacyPostCopy();
        createCommunityIndexes();
    }

    private void createCommunityTables() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS posts (
                  id INT PRIMARY KEY AUTO_INCREMENT,
                  openid VARCHAR(100) NOT NULL,
                  post_type VARCHAR(32) NOT NULL DEFAULT 'general',
                  title VARCHAR(80) NOT NULL,
                  summary VARCHAR(255),
                  content TEXT NOT NULL,
                  images JSON,
                  board_id INT,
                  board_name VARCHAR(100),
                  role_tags JSON,
                  tag_list JSON,
                  session_id VARCHAR(64),
                  quality_score INT DEFAULT 0,
                  hot_score DOUBLE DEFAULT 0,
                  view_count INT DEFAULT 0,
                  like_count INT DEFAULT 0,
                  comment_count INT DEFAULT 0,
                  favorite_count INT DEFAULT 0,
                  status VARCHAR(20) NOT NULL DEFAULT 'PUBLISHED',
                  featured TINYINT DEFAULT 0,
                  pinned TINYINT DEFAULT 0,
                  reject_reason VARCHAR(255),
                  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
                  update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
                )
                """);

        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS comments (
                  id INT PRIMARY KEY AUTO_INCREMENT,
                  post_id INT NOT NULL,
                  openid VARCHAR(100) NOT NULL,
                  parent_comment_id INT,
                  reply_to_openid VARCHAR(100),
                  content VARCHAR(600) NOT NULL,
                  like_count INT DEFAULT 0,
                  status VARCHAR(20) NOT NULL DEFAULT 'VISIBLE',
                  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
                  update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
                )
                """);

        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS user_favorite_posts (
                  id BIGINT PRIMARY KEY AUTO_INCREMENT,
                  openid VARCHAR(100) NOT NULL,
                  post_id INT NOT NULL,
                  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
                  UNIQUE KEY uk_user_favorite_post (openid, post_id)
                )
                """);
    }

    private void ensureCommunityColumns() {
        createColumnIfMissing("posts", "post_type",
                "ALTER TABLE posts ADD COLUMN post_type VARCHAR(32) NOT NULL DEFAULT 'general' AFTER openid");
        createColumnIfMissing("posts", "title",
                "ALTER TABLE posts ADD COLUMN title VARCHAR(80) NOT NULL DEFAULT '未命名帖子' AFTER post_type");
        createColumnIfMissing("posts", "summary",
                "ALTER TABLE posts ADD COLUMN summary VARCHAR(255) AFTER title");
        createColumnIfMissing("posts", "board_id",
                "ALTER TABLE posts ADD COLUMN board_id INT AFTER images");
        createColumnIfMissing("posts", "board_name",
                "ALTER TABLE posts ADD COLUMN board_name VARCHAR(100) AFTER board_id");
        createColumnIfMissing("posts", "role_tags",
                "ALTER TABLE posts ADD COLUMN role_tags JSON AFTER board_name");
        createColumnIfMissing("posts", "tag_list",
                "ALTER TABLE posts ADD COLUMN tag_list JSON AFTER role_tags");
        createColumnIfMissing("posts", "session_id",
                "ALTER TABLE posts ADD COLUMN session_id VARCHAR(64) AFTER tag_list");
        createColumnIfMissing("posts", "quality_score",
                "ALTER TABLE posts ADD COLUMN quality_score INT DEFAULT 0 AFTER session_id");
        createColumnIfMissing("posts", "hot_score",
                "ALTER TABLE posts ADD COLUMN hot_score DOUBLE DEFAULT 0 AFTER quality_score");
        createColumnIfMissing("posts", "view_count",
                "ALTER TABLE posts ADD COLUMN view_count INT DEFAULT 0 AFTER hot_score");
        createColumnIfMissing("posts", "favorite_count",
                "ALTER TABLE posts ADD COLUMN favorite_count INT DEFAULT 0 AFTER comment_count");
        createColumnIfMissing("posts", "featured",
                "ALTER TABLE posts ADD COLUMN featured TINYINT DEFAULT 0 AFTER status");
        createColumnIfMissing("posts", "pinned",
                "ALTER TABLE posts ADD COLUMN pinned TINYINT DEFAULT 0 AFTER featured");
        createColumnIfMissing("posts", "reject_reason",
                "ALTER TABLE posts ADD COLUMN reject_reason VARCHAR(255) AFTER pinned");

        createColumnIfMissing("comments", "parent_comment_id",
                "ALTER TABLE comments ADD COLUMN parent_comment_id INT AFTER openid");
        createColumnIfMissing("comments", "reply_to_openid",
                "ALTER TABLE comments ADD COLUMN reply_to_openid VARCHAR(100) AFTER parent_comment_id");
        createColumnIfMissing("comments", "update_time",
                "ALTER TABLE comments ADD COLUMN update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP");

        createColumnIfMissing("user_favorite_posts", "id",
                """
                        ALTER TABLE user_favorite_posts
                        ADD COLUMN id BIGINT PRIMARY KEY AUTO_INCREMENT FIRST
                        """);
    }

    private void normalizeLegacyStatuses() {
        String postStatusType = findColumnType("posts", "status");
        if (!"varchar".equalsIgnoreCase(postStatusType)) {
            jdbcTemplate.execute("ALTER TABLE posts MODIFY COLUMN status VARCHAR(20) NOT NULL DEFAULT 'PUBLISHED'");
        }
        jdbcTemplate.execute("""
                UPDATE posts
                SET status = CASE
                  WHEN status IS NULL OR status = '' THEN 'PUBLISHED'
                  WHEN status = '1' THEN 'PUBLISHED'
                  WHEN status = '0' THEN 'OFFLINE'
                  ELSE status
                END
                """);

        String commentStatusType = findColumnType("comments", "status");
        if (!"varchar".equalsIgnoreCase(commentStatusType)) {
            jdbcTemplate.execute("ALTER TABLE comments MODIFY COLUMN status VARCHAR(20) NOT NULL DEFAULT 'VISIBLE'");
        }
        jdbcTemplate.execute("""
                UPDATE comments
                SET status = CASE
                  WHEN status IS NULL OR status = '' THEN 'VISIBLE'
                  WHEN status = '1' THEN 'VISIBLE'
                  WHEN status = '0' THEN 'HIDDEN'
                  ELSE status
                END
                """);
    }

    private void backfillLegacyPostCopy() {
        jdbcTemplate.execute("ALTER TABLE posts MODIFY COLUMN title VARCHAR(80) NOT NULL DEFAULT '未命名帖子'");
        jdbcTemplate.execute("""
                UPDATE posts
                SET title = LEFT(TRIM(REPLACE(REPLACE(content, '\r', ' '), '\n', ' ')), 40)
                WHERE (title IS NULL OR TRIM(title) = '' OR LOWER(TRIM(title)) = 'untitled post')
                  AND content IS NOT NULL
                  AND TRIM(content) <> ''
                """);
        jdbcTemplate.execute("""
                UPDATE posts
                SET summary = LEFT(TRIM(REPLACE(REPLACE(content, '\r', ' '), '\n', ' ')), 120)
                WHERE (summary IS NULL OR TRIM(summary) = '')
                  AND content IS NOT NULL
                  AND TRIM(content) <> ''
                """);
    }

    private void createCommunityIndexes() {
        createIndexIfMissing("posts", "idx_posts_status_feed",
                "CREATE INDEX idx_posts_status_feed ON posts (status, pinned, featured, create_time)");
        createIndexIfMissing("posts", "idx_posts_board_feed",
                "CREATE INDEX idx_posts_board_feed ON posts (board_id, status, create_time)");
        createIndexIfMissing("posts", "idx_posts_type_feed",
                "CREATE INDEX idx_posts_type_feed ON posts (post_type, status, create_time)");
        createIndexIfMissing("comments", "idx_comments_post_status",
                "CREATE INDEX idx_comments_post_status ON comments (post_id, status, create_time)");
        createIndexIfMissing("user_favorite_posts", "idx_user_favorite_posts_openid",
                "CREATE INDEX idx_user_favorite_posts_openid ON user_favorite_posts (openid, create_time)");
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

    private String findColumnType(String tableName, String columnName) {
        return jdbcTemplate.query(
                """
                        SELECT data_type
                        FROM information_schema.columns
                        WHERE table_schema = DATABASE()
                          AND table_name = ?
                          AND column_name = ?
                        """,
                rs -> rs.next() ? rs.getString(1) : "",
                tableName,
                columnName
        );
    }
}
