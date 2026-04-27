package com.wolfbook.backend.ai.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;

@Configuration
@EnableConfigurationProperties(AiProperties.class)
public class AiPostgresConfiguration {

    @Bean("aiDataSource")
    @ConditionalOnProperty(prefix = "wolfbook.ai.postgres", name = "enabled", havingValue = "true")
    DataSource aiDataSource(AiProperties properties) {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.postgresql.Driver");
        dataSource.setUrl(properties.getPostgres().getUrl());
        dataSource.setUsername(properties.getPostgres().getUsername());
        dataSource.setPassword(properties.getPostgres().getPassword());
        return dataSource;
    }

    @Bean("aiJdbcTemplate")
    @ConditionalOnProperty(prefix = "wolfbook.ai.postgres", name = "enabled", havingValue = "true")
    JdbcTemplate aiJdbcTemplate(@Qualifier("aiDataSource") DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }
}
