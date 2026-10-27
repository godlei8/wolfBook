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
 * 给已有库补上板子背景图、详细规则和角色主题色三列。
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
    }

    private void addColumnIfMissing(String table, String column, String ddl) {
        if (existingColumns(table).contains(column)) {
            return;
        }
        log.info("Adding missing column {}.{}", table, column);
        jdbcTemplate.execute(ddl);
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
