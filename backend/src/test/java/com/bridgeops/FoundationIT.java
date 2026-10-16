package com.bridgeops;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "bridgeops.api-docs.enabled=true")
@AutoConfigureMockMvc
@Testcontainers
class FoundationIT {
    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer(
            DockerImageName.parse("postgres@sha256:77f585114c32fbca283dc835b0596f4e52b51b4c6662d7810b2f4084f60a1873")
                    .asCompatibleSubstituteFor("postgres"));

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;

    @Test
    void emptyDatabaseMigratesAndRecordsSuccessfulVersion() {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT bootstrap_consumed_at IS NULL FROM installation_state",Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("SELECT schema_purpose FROM platform_metadata WHERE singleton_id=1", String.class))
                .isEqualTo("bridgeops-foundation");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE success=true AND version='1'", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void probesAreMinimalAndReadyAfterMigration() throws Exception {
        mvc.perform(get("/actuator/health/liveness"))
                .andExpect(status().isOk()).andExpect(content().json("{\"status\":\"UP\"}"))
                .andExpect(jsonPath("$.components").doesNotExist());
        mvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void optInOpenApiDescribesOnlyImplementedBusinessRoute() throws Exception {
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/system/info'].get").exists())
                .andExpect(jsonPath("$.paths['/api/v1/auth/login']").doesNotExist());
    }
}
