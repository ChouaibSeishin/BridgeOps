package com.bridgeops.executions;

import com.bridgeops.approvals.application.ApprovalIntent;
import com.bridgeops.approvals.domain.ApprovalState;
import com.bridgeops.approvals.persistence.ApprovalStore;
import com.bridgeops.executions.domain.*;
import com.bridgeops.executions.persistence.ExecutionStore;
import com.bridgeops.organizations.persistence.OrganizationStore;
import com.bridgeops.workflows.application.WorkflowDefinitions;
import com.bridgeops.workflows.domain.GraphDefinition;
import com.bridgeops.workflows.persistence.WorkflowStore;
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
class GraphDataMigrationIT {
    @Container static final PostgreSQLContainer postgres = new PostgreSQLContainer(DockerImageName.parse("postgres@sha256:77f585114c32fbca283dc835b0596f4e52b51b4c6662d7810b2f4084f60a1873").asCompatibleSubstituteFor("postgres")).withInitScript("organization-runtime.sql");
    String database() { String name = "graph_data_" + UUID.randomUUID().toString().replace("-", ""); new JdbcTemplate(new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())).execute("CREATE DATABASE " + name); return postgres.getJdbcUrl().replace("/" + postgres.getDatabaseName(), "/" + name); }
    Flyway migrate(String url, String target) { return Flyway.configure().dataSource(url, postgres.getUsername(), postgres.getPassword()).locations("classpath:db/migration").target(target).load(); }
    JdbcTemplate db(String url) { return new JdbcTemplate(new DriverManagerDataSource(url, postgres.getUsername(), postgres.getPassword())); }
    @Test void freshV16ProvisionsNoActorsOrWorkAndKeepsGraphDataAppendOnly() {
        String url = database(); assertThat(migrate(url, "16").migrate().migrationsExecuted).isEqualTo(16); var jdbc = db(url);
        for (String table : List.of("users", "workflow_executions", "automation_principals", "execution_step_inputs", "execution_step_routes", "execution_step_delays")) assertThat(jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class)).isZero();
        for (String table : List.of("execution_step_inputs", "execution_step_routes", "execution_step_delays")) for (String privilege : List.of("UPDATE", "DELETE", "TRUNCATE")) assertThat(jdbc.queryForObject("SELECT has_table_privilege('bridgeops_app',?,?)", Boolean.class, table, privilege)).isFalse();
        assertThat(jdbc.queryForObject("SELECT has_column_privilege('bridgeops_app','workflow_executions','graph_schema_version','UPDATE')", Boolean.class)).isFalse();
    }
    @Test void populatedV11UpgradePreservesOriginalColumnsIncludingApprovedIntentDecisionAndReceipts() {
        String url = database(); migrate(url, "11").migrate(); var jdbc = db(url); seedV11(jdbc);
        var tables = jdbc.queryForList("SELECT tablename FROM pg_tables WHERE schemaname='public' AND tablename<>'flyway_schema_history' ORDER BY tablename", String.class);
        Map<String, String> projection = new TreeMap<>(); Map<String, List<Map<String, Object>>> rows = new TreeMap<>();
        for (String table : tables) { String columns = jdbc.queryForObject("SELECT string_agg(quote_ident(column_name),',' ORDER BY ordinal_position) FROM information_schema.columns WHERE table_schema='public' AND table_name=?", String.class, table); projection.put(table, columns); rows.put(table, jdbc.queryForList("SELECT " + columns + " FROM " + table)); }
        for (String table : List.of("users", "installation_state", "workflows", "workflow_versions", "workflow_executions", "execution_steps", "execution_attempts", "execution_events", "synthetic_records", "execution_operation_receipts", "approval_requests", "approval_decisions", "approval_signals", "approval_signal_receipts", "approval_events")) assertThat(rows.get(table)).as(table).isNotEmpty();
        assertThat(migrate(url, "16").migrate().migrationsExecuted).isEqualTo(5);
        rows.forEach((table, before) -> assertThat(jdbc.queryForList("SELECT " + projection.get(table) + " FROM " + table)).as(table).containsExactlyInAnyOrderElementsOf(before));
        assertThat(jdbc.queryForObject("SELECT bootstrap_consumed_at IS NOT NULL FROM installation_state", Boolean.class)).isTrue();
        var runtime = new JdbcTemplate(new DriverManagerDataSource(url, "bridgeops_app", "synthetic-runtime-fixture"));
        assertThatThrownBy(() -> runtime.update("UPDATE execution_steps SET output_json=?", "{\"message\":\"" + "x".repeat(5000) + "\"}")).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(() -> runtime.update("UPDATE workflow_executions SET graph_schema_version=2")).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE approval_decisions SET reason='Cannot rewrite'")).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM execution_step_inputs", Long.class)).isZero();
    }
    void seedV11(JdbcTemplate jdbc) {
        Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS); var time = java.sql.Timestamp.from(now); UUID owner = UUID.randomUUID(), decider = UUID.randomUUID();
        String password = new com.bridgeops.identity.application.AccountPasswords().hash("graph-data-disposable-upgrade-password");
        for (UUID user : List.of(owner, decider)) jdbc.update("INSERT INTO users(id,email,normalized_email,password_hash,display_name,account_status,created_at,email_verified_at) VALUES(?,?,?,?,?,'ACTIVE',?,?)", user, user + "@example.invalid", user + "@example.invalid", password, "Synthetic", time, time);
        jdbc.update("UPDATE installation_state SET bootstrap_consumed_at=?,initial_user_id=? WHERE singleton_id=1", time, owner); UUID org = new OrganizationStore(jdbc).insert(owner, "Preserved V11", "preserved-v11", now).id();
        UUID membership = UUID.randomUUID(); jdbc.update("INSERT INTO organization_memberships(id,organization_id,user_id,status,joined_at) VALUES(?,?,?,'ACTIVE',?)", membership, org, decider, time); jdbc.update("INSERT INTO membership_roles SELECT ?,?,id FROM roles WHERE organization_id=? AND name='APPROVER'", org, membership, org);
        var graphs = new GraphDefinition(); var workflows = new WorkflowStore(jdbc);
        String json = """
            {"schemaVersion":1,"nodes":[{"id":"start","type":"manualTrigger","typeVersion":1,"config":{}},{"id":"review","type":"approval","typeVersion":1,"config":{"prompt":"Preserve exact review"}},{"id":"record","type":"syntheticAction","typeVersion":1,"config":{"operation":"record","message":"Preserved approved record"}},{"id":"end","type":"end","typeVersion":1,"config":{}}],"edges":[{"source":"start","target":"review"},{"source":"review","target":"record"},{"source":"record","target":"end"}]}
            """;
        var canonical = graphs.canonical(graphs.parse(json)); UUID workflow = workflows.create(org, owner, "Historical approved workflow", "", json, now); UUID version = workflows.publish(workflows.get(org, workflow, false), owner, canonical.json(), canonical.hash(), now); workflows.point(workflows.get(org, workflow, false), version, now);
        var executions = new ExecutionStore(jdbc); UUID execution = UUID.randomUUID(); executions.insert(execution, org, workflow, version, canonical.hash(), owner, "MANUAL", "preserved-v11-start", "a".repeat(64), null, now, now);
        var plan = ExecutionPlan.from(graphs.parse(canonical.json()), new WorkflowDefinitions(graphs)); for (int i = 0; i < plan.size(); i++) { var node = plan.get(i); executions.insertStep(org, execution, node.id(), i, node.type(), UUID.randomUUID(), node.argumentHash()); }
        var action = executions.steps(org, execution).stream().filter(s -> s.node().equals("record")).findFirst().orElseThrow(); var approvals = new ApprovalStore(jdbc); approvals.ensurePolicy(org, now);
        var intent = ApprovalIntent.record(org, execution, "review", "record", version, canonical.hash(), owner, action.operation(), 0, approvals.policy(org).snapshot(), "Preserve exact review", "Preserved approved record", now, now.plusSeconds(3600), now.plusSeconds(86400));
        var request = approvals.insert(intent, action.argumentHash(), now); approvals.decide(request, decider, "APPROVE", "Approved exact fixture", now.plusSeconds(1)); approvals.finalize(request, ApprovalState.APPROVED, null, now.plusSeconds(1)); approvals.event(request, decider, "APPROVAL_APPROVED", now.plusSeconds(1)); approvals.consume(org, approvals.signals(org, execution).getFirst(), now.plusSeconds(2));
        var row = executions.get(org, execution, false); executions.state(row, ExecutionState.RUNNING, null, now); var done = java.sql.Timestamp.from(now.plusSeconds(2));
        jdbc.update("UPDATE execution_steps SET status='SUCCEEDED',attempt_count=1,attempt_started_at=?,completed_at=?,output_json='{}' WHERE execution_id=?", time, done, execution);
        jdbc.update("UPDATE execution_steps SET output_json='{\"recorded\":true}' WHERE execution_id=? AND node_id='record'",execution);
        jdbc.update("UPDATE execution_steps SET output_json='{\"approved\":true}' WHERE execution_id=? AND node_id='review'",execution);
        for (var step : executions.steps(org, execution)) jdbc.update("INSERT INTO execution_attempts(id,organization_id,execution_id,node_id,attempt_number,started_at,completed_at,outcome) VALUES(?,?,?,?,1,?,?,'SUCCEEDED')", UUID.randomUUID(), org, execution, step.node(), time, done);
        jdbc.update("INSERT INTO synthetic_records VALUES(?,?,?,?)", org, action.operation(), "Preserved approved record", done); jdbc.update("INSERT INTO execution_operation_receipts VALUES(?,?,'synthetic.record','record',?,'{\"recorded\":true}',?)", org, action.operation(), action.argumentHash(), done);
        row = executions.get(org, execution, false); executions.event(row, "RECORD_COMMITTED", "record", now.plusSeconds(2)); executions.state(row, ExecutionState.SUCCEEDED, null, now.plusSeconds(2)); executions.done(row);
    }
}
