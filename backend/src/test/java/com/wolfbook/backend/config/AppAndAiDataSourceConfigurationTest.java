package com.wolfbook.backend.config;

import com.wolfbook.backend.ai.config.AiPostgresConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class AppAndAiDataSourceConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    ConfigurationPropertiesAutoConfiguration.class,
                    DataSourceAutoConfiguration.class
            ))
            .withUserConfiguration(
                    AppDataSourceConfiguration.class,
                    AiPostgresConfiguration.class,
                    AppJdbcConfiguration.class
            )
            .withPropertyValues(
                    "spring.datasource.url=jdbc:h2:mem:mainapp;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE",
                    "spring.datasource.username=sa",
                    "spring.datasource.password=",
                    "spring.datasource.driver-class-name=org.h2.Driver",
                    "wolfbook.ai.postgres.enabled=true",
                    "wolfbook.ai.postgres.url=jdbc:postgresql://127.0.0.1:5433/wolfbook_ai",
                    "wolfbook.ai.postgres.username=wolfbook_ai",
                    "wolfbook.ai.postgres.password="
            );

    @Test
    void mainDataSourceShouldStillExistWhenAiDataSourceIsEnabled() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasBean("dataSource");
            assertThat(context).hasBean("appJdbcTemplate");
            assertThat(context).hasBean("aiDataSource");
            assertThat(context).hasBean("aiJdbcTemplate");
        });
    }
}
