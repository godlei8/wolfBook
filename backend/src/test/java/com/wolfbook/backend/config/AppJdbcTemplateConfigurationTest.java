package com.wolfbook.backend.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:appjdbc;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE"
})
class AppJdbcTemplateConfigurationTest {

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    void appJdbcTemplateBeanIsAvailable() {
        assertThat(applicationContext.containsBean("appJdbcTemplate")).isTrue();
        assertThat(applicationContext.getBean("appJdbcTemplate")).isInstanceOf(JdbcTemplate.class);
    }
}
