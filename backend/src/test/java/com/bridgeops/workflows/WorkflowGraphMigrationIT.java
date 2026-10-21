package com.bridgeops.workflows;

import com.bridgeops.identity.application.AccountPasswords;
import com.bridgeops.organizations.persistence.OrganizationStore;
import com.bridgeops.workflows.persistence.WorkflowStore;
import com.bridgeops.workflows.domain.GraphDefinition;
import java.time.*;
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
class WorkflowGraphMigrationIT {
    @Container static final PostgreSQLContainer postgres = new PostgreSQLContainer(DockerImageName.parse("postgres@sha256:77f585114c32fbca283dc835b0596f4e52b51b4c6662d7810b2f4084f60a1873").asCompatibleSubstituteFor("postgres")).withInitScript("organization-runtime.sql");
    String database() { String name = "graph_" + UUID.randomUUID().toString().replace("-", ""); new JdbcTemplate(new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())).execute("CREATE DATABASE " + name); return postgres.getJdbcUrl().replace("/" + postgres.getDatabaseName(), "/" + name); }
    Flyway migration(String url, String target) { return Flyway.configure().dataSource(url, postgres.getUsername(), postgres.getPassword()).locations("classpath:db/migration").target(target).load(); }
    JdbcTemplate operator(String url) { return new JdbcTemplate(new DriverManagerDataSource(url, postgres.getUsername(), postgres.getPassword())); }
    record Fixture(UUID user, UUID organization, UUID workflow, UUID version) {}
    Fixture fixture(JdbcTemplate db) {
        var now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS); UUID user = UUID.randomUUID(); var time = java.sql.Timestamp.from(now);
        db.update("INSERT INTO users(id,email,normalized_email,password_hash,display_name,account_status,created_at,email_verified_at) VALUES(?,'graph@example.invalid','graph@example.invalid',?,'Synthetic','ACTIVE',?,?)", user, new AccountPasswords().hash("disposable-graph-migration-password"), time, time);
        db.update("UPDATE installation_state SET bootstrap_consumed_at=?,initial_user_id=? WHERE singleton_id=1", time, user);
        UUID org = new OrganizationStore(db).insert(user, "Preserved", "preserved-graph", now).id(); var workflows = new WorkflowStore(db); var graphs = new GraphDefinition();
        UUID workflow = workflows.create(org, user, "Existing definition", "Historical v1", GraphDefinitionTest.GRAPH, now);
        var canonical = graphs.canonical(graphs.parse(GraphDefinitionTest.GRAPH));
        UUID version = workflows.publish(workflows.get(org, workflow, false), user, canonical.json(), canonical.hash(), now); workflows.point(workflows.get(org, workflow, false), version, now);
        return new Fixture(user, org, workflow, version);
    }
    @Test void freshSchemaPreservesRuntimeImmutabilityAndProvisioningBoundary() {
        String url = database(); assertThat(migration(url, "14").migrate().migrationsExecuted).isEqualTo(14); var db = operator(url);
        for (String table : List.of("users", "organizations", "workflows", "workflow_versions", "workflow_executions", "approval_requests", "integration_connections")) assertThat(db.queryForObject("SELECT count(*) FROM " + table, Long.class)).isZero();
        for (String privilege : List.of("UPDATE", "DELETE", "TRUNCATE")) assertThat(db.queryForObject("SELECT has_table_privilege('bridgeops_app','workflow_versions',?)", Boolean.class, privilege)).isFalse();
        assertThat(db.queryForObject("SELECT has_table_privilege('bridgeops_app','users','INSERT')", Boolean.class)).isFalse();
    }
    @Test void populatedV11UpgradePreservesEveryOriginalRowAndPublishedBytes() {
        String url = database(); migration(url, "11").migrate(); var db = operator(url); var fixture = fixture(db);
        Map<String, List<Map<String, Object>>> before = new TreeMap<>();
        for (String table : db.queryForList("SELECT tablename FROM pg_tables WHERE schemaname='public' AND tablename<>'flyway_schema_history' ORDER BY tablename", String.class)) before.put(table, db.queryForList("SELECT * FROM " + table));
        assertThat(migration(url, "14").migrate().migrationsExecuted).isEqualTo(3);
        before.forEach((table, rows) -> assertThat(db.queryForList("SELECT * FROM " + table)).as(table).containsExactlyInAnyOrderElementsOf(rows));
        assertThat(new WorkflowStore(db).version(fixture.organization(), fixture.workflow(), fixture.version()).schemaVersion()).isEqualTo(1);
    }
    @Test void v2VersionPairAndDeclaredSchemaAreEnforcedWithoutAllowingHistoryMutation() {
        String url = database(); migration(url, "14").migrate(); var db = operator(url); var f = fixture(db); var runtime = new JdbcTemplate(new DriverManagerDataSource(url, "bridgeops_app", "synthetic-runtime-fixture"));
        var store = new WorkflowStore(runtime); var graphs = new GraphDefinition();
        UUID workflow = store.create(f.organization(), f.user(), "V2 definition", "", GraphV2DefinitionTest.GRAPH, Instant.now()); var row = store.get(f.organization(), workflow, false); var canonical = graphs.canonical(graphs.parse(GraphV2DefinitionTest.GRAPH));
        UUID id = store.publish(row, f.user(), canonical.json(), canonical.hash(), 2, 2, Instant.now());
        var snapshot = store.version(f.organization(), workflow, id); assertThat(snapshot.schemaVersion()).isEqualTo(2); assertThat(snapshot.canonicalizationVersion()).isEqualTo(2); assertThat(snapshot.definition()).isEqualTo(canonical.json());
        assertThatThrownBy(() -> store.publish(row, f.user(), canonical.json(), canonical.hash(), Instant.now())).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(() -> store.publish(row, f.user(), canonical.json(), canonical.hash(), 2, 1, Instant.now())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> db.update("UPDATE workflow_versions SET name='Cannot rewrite' WHERE id=?", id)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(() -> runtime.update("UPDATE workflow_versions SET definition_hash=repeat('a',64) WHERE id=?", id)).isInstanceOf(org.springframework.dao.DataAccessException.class);
    }
}
