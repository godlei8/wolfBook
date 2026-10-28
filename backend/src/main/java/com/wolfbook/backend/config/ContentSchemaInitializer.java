package com.wolfbook.backend.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.ResultSetMetaData;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * 给已有库补上板子背景图、详细规则、角色主题色 / 称号 / 人物关系，帖子的标题、分类、关联板子这几列，
 * 以及世界观表 world_lore。
 * <p>
 * 和其他 SchemaInitializer 不同，这里不限定 profile：这三列已经映射进 BoardEntity / RoleEntity，
 * 用 test profile 连本地 MySQL 的开发脚本（start-backend-dev.ps1）也必须有这三列，否则查询会报未知列。
 * H2 单测的 schema-h2.sql 已经带上这三列，这里检查后什么都不做。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ContentSchemaInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ContentSchemaInitializer.class);

    private final JdbcTemplate jdbcTemplate;

    public ContentSchemaInitializer(@Qualifier("appJdbcTemplate") JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        addColumnIfMissing("boards", "background_image", "ALTER TABLE boards ADD COLUMN background_image VARCHAR(500) NULL");
        addColumnIfMissing("boards", "rule_detail", "ALTER TABLE boards ADD COLUMN rule_detail JSON NULL");
        addColumnIfMissing("roles", "theme_color", "ALTER TABLE roles ADD COLUMN theme_color VARCHAR(16) NULL");
        // 世界观：角色称号、人物关系，以及整份编年史
        addColumnIfMissing("roles", "title", "ALTER TABLE roles ADD COLUMN title VARCHAR(40) NULL");
        addColumnIfMissing("roles", "relations", "ALTER TABLE roles ADD COLUMN relations JSON NULL");
        // AI 助手可切换的对话模型
        createTableIfMissing("assistant_models", "CREATE TABLE assistant_models (id INT PRIMARY KEY AUTO_INCREMENT, name VARCHAR(60) NOT NULL, "
                + "base_url VARCHAR(300) NOT NULL, model VARCHAR(120) NOT NULL, api_key VARCHAR(500), enabled TINYINT DEFAULT 1, active TINYINT DEFAULT 0, "
                + "remark VARCHAR(200), create_time DATETIME DEFAULT CURRENT_TIMESTAMP, update_time DATETIME DEFAULT CURRENT_TIMESTAMP)");
        addColumnIfMissing("assistant_models", "disable_thinking", "ALTER TABLE assistant_models ADD COLUMN disable_thinking TINYINT DEFAULT 0");
        createTableIfMissing("world_lore", "CREATE TABLE world_lore (id INT PRIMARY KEY, content TEXT, update_time DATETIME DEFAULT CURRENT_TIMESTAMP)");
        // 帖子的标题、分类和关联板子（酒馆页的分类筛选和板子标签）
        addColumnIfMissing("posts", "title", "ALTER TABLE posts ADD COLUMN title VARCHAR(60) NULL");
        addColumnIfMissing("posts", "category", "ALTER TABLE posts ADD COLUMN category VARCHAR(16) NULL");
        addColumnIfMissing("posts", "board_id", "ALTER TABLE posts ADD COLUMN board_id INT NULL");
        addColumnIfMissing("posts", "board_name", "ALTER TABLE posts ADD COLUMN board_name VARCHAR(100) NULL");
        // 法官房的 4 位短房号（全新库里 judge_rooms 由 JudgeSchemaInitializer 建，建表语句已带这一列）
        addColumnIfMissing("judge_rooms", "room_code", "ALTER TABLE judge_rooms ADD COLUMN room_code VARCHAR(8) NULL");
    }

    private void addColumnIfMissing(String table, String column, String ddl) {
        Set<String> columns;
        try {
            columns = existingColumns(table);
        } catch (RuntimeException missingTable) {
            // 表还没建（全新库），建表语句自己会带上这一列
            return;
        }
        if (columns.contains(column)) {
            return;
        }
        log.info("Adding missing column {}.{}", table, column);
        jdbcTemplate.execute(ddl);
    }

    private void createTableIfMissing(String table, String ddl) {
        try {
            existingColumns(table);
        } catch (RuntimeException missingTable) {
            log.info("Creating missing table {}", table);
            jdbcTemplate.execute(ddl);
        }
    }

    /** 用一次空查询读结果集元数据，MySQL 和 H2 都适用，不依赖 information_schema 的方言差异。 */
    private Set<String> existingColumns(String table) {
        return jdbcTemplate.query("SELECT * FROM " + table + " WHERE 1 = 0", resultSet -> {
            ResultSetMetaData metaData = resultSet.getMetaData();
            Set<String> columns = new HashSet<>();
            for (int index = 1; index <= metaData.getColumnCount(); index++) {
                columns.add(metaData.getColumnName(index).toLowerCase(Locale.ROOT));
            }
            return columns;
        });
    }
}
