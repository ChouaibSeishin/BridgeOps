package com.bridgeops.integrations;

import com.bridgeops.organizations.persistence.OrganizationStore;
import com.bridgeops.workflows.persistence.WorkflowStore;
import com.bridgeops.workflows.domain.GraphDefinition;
import com.bridgeops.workflows.GraphV2DefinitionTest;
import java.time.Instant;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
class AutomationMigrationIT {
    @Container static final PostgreSQLContainer postgres = new PostgreSQLContainer(DockerImageName.parse("postgres@sha256:77f585114c32fbca283dc835b0596f4e52b51b4c6662d7810b2f4084f60a1873").asCompatibleSubstituteFor("postgres")).withInitScript("organization-runtime.sql");
    String database() { String name = "principal_" + UUID.randomUUID().toString().replace("-", ""); new JdbcTemplate(new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())).execute("CREATE DATABASE " + name); return postgres.getJdbcUrl().replace("/" + postgres.getDatabaseName(), "/" + name); }
    Flyway migration(String url, String target) { return Flyway.configure().dataSource(url, postgres.getUsername(), postgres.getPassword()).locations("classpath:db/migration").target(target).load(); }
    JdbcTemplate db(String url) { return new JdbcTemplate(new DriverManagerDataSource(url, postgres.getUsername(), postgres.getPassword())); }
    @Test void freshV15HasNoFakeUsersPrincipalsOrExecutionsAndNarrowGrants() {
        String url = database(); assertThat(migration(url, "15").migrate().migrationsExecuted).isEqualTo(15); var jdbc = db(url);
        for (String table : List.of("users", "automation_principals", "automation_operation_grants", "workflow_executions", "approval_requests")) assertThat(jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class)).isZero();
        for (String column : List.of("name", "created_by", "workflow_version_id", "definition_hash")) assertThat(jdbc.queryForObject("SELECT has_column_privilege('bridgeops_app','automation_principals',?,'UPDATE')", Boolean.class, column)).isFalse();
        for (String privilege : List.of("UPDATE", "DELETE", "TRUNCATE")) assertThat(jdbc.queryForObject("SELECT has_table_privilege('bridgeops_app','automation_operation_grants',?)", Boolean.class, privilege)).isFalse();
    }
    @Test void populatedV14UpgradePreservesAllExistingTablesAndVersionBytes() {
        String url = database(); migration(url, "14").migrate(); var jdbc = db(url); var now = Instant.now(); var time = java.sql.Timestamp.from(now); UUID actor = UUID.randomUUID();
        jdbc.update("INSERT INTO users(id,email,normalized_email,password_hash,display_name,account_status,created_at,email_verified_at) VALUES(?,'principal-upgrade@example.invalid','principal-upgrade@example.invalid',?,'Synthetic','ACTIVE',?,?)", actor, new com.bridgeops.identity.application.AccountPasswords().hash("principal-disposable-upgrade-password"), time, time);
        jdbc.update("UPDATE installation_state SET bootstrap_consumed_at=?,initial_user_id=? WHERE singleton_id=1", time, actor);
        UUID org = new OrganizationStore(jdbc).insert(actor, "Preserve scope", "preserve-scope", now).id(); var store = new WorkflowStore(jdbc); var graphs = new GraphDefinition(); var canonical = graphs.canonical(graphs.parse(GraphV2DefinitionTest.GRAPH)); UUID workflow = store.create(org, actor, "Saved v2", "", canonical.json(), now); store.publish(store.get(org, workflow, false), actor, canonical.json(), canonical.hash(), 2, 2, now);
        Map<String, List<Map<String, Object>>> before = new TreeMap<>(); for (String table : jdbc.queryForList("SELECT tablename FROM pg_tables WHERE schemaname='public' AND tablename<>'flyway_schema_history' ORDER BY tablename", String.class)) before.put(table, jdbc.queryForList("SELECT * FROM " + table));
        assertThat(migration(url, "15").migrate().migrationsExecuted).isEqualTo(1); before.forEach((table, rows) -> assertThat(jdbc.queryForList("SELECT * FROM " + table)).as(table).containsExactlyInAnyOrderElementsOf(rows)); assertThat(jdbc.queryForObject("SELECT count(*) FROM automation_principals", Long.class)).isZero();
    }
}
