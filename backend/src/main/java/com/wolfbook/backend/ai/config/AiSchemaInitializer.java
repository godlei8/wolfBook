package com.wolfbook.backend.ai.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

@Component
public class AiSchemaInitializer implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(AiSchemaInitializer.class);

    private final AiProperties properties;
    private final ObjectProvider<JdbcTemplate> jdbcTemplateProvider;

    public AiSchemaInitializer(AiProperties properties, @Qualifier("aiJdbcTemplate") ObjectProvider<JdbcTemplate> jdbcTemplateProvider) {
        this.properties = properties;
        this.jdbcTemplateProvider = jdbcTemplateProvider;
    }

    @Override
    public void run(String... args) throws Exception {
        if (!properties.getPostgres().isEnabled() || !properties.getPostgres().isInitialize()) {
            return;
        }
        JdbcTemplate jdbcTemplate = jdbcTemplateProvider.getIfAvailable();
        if (jdbcTemplate == null) {
            return;
        }
        String schema = new ClassPathResource("ai/schema-postgres.sql")
                .getContentAsString(StandardCharsets.UTF_8);
        for (String statement : schema.split(";\\s*(?:\\r?\\n|$)")) {
            String sql = statement.trim();
            if (!sql.isBlank()) {
                jdbcTemplate.execute(sql);
            }
        }
        log.info("AI PostgreSQL schema initialized");
    }
}
