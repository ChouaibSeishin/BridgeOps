package com.bridgeops.approvals;

import com.bridgeops.executions.persistence.ExecutionStore;
import com.bridgeops.executions.domain.ExecutionState;
import com.bridgeops.identity.application.AccountPasswords;
import com.bridgeops.organizations.persistence.OrganizationStore;
import com.bridgeops.workflows.persistence.WorkflowStore;
import com.bridgeops.workflows.domain.GraphDefinition;
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

/** Separate disposable databases prove the real V10 upgrade without local user fixtures. */
@Testcontainers
class ApprovalMigrationIT {
    @Container static final PostgreSQLContainer postgres=new PostgreSQLContainer(DockerImageName.parse("postgres@sha256:77f585114c32fbca283dc835b0596f4e52b51b4c6662d7810b2f4084f60a1873").asCompatibleSubstituteFor("postgres")).withInitScript("organization-runtime.sql");
    String database(){String name="approval_"+UUID.randomUUID().toString().replace("-","");new JdbcTemplate(new DriverManagerDataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword())).execute("CREATE DATABASE "+name);return postgres.getJdbcUrl().replace("/"+postgres.getDatabaseName(),"/"+name);}
    JdbcTemplate operator(String url){return new JdbcTemplate(new DriverManagerDataSource(url,postgres.getUsername(),postgres.getPassword()));}
    Flyway migration(String url,String target){var c=Flyway.configure().dataSource(url,postgres.getUsername(),postgres.getPassword()).locations("classpath:db/migration");c.target(target==null?"11":target);return c.load();}
    @Test void freshV11CreatesNoAccountsPoliciesOrRequestsAndRestrictsHistoricalWrites(){
        String url=database();assertThat(migration(url,null).migrate().migrationsExecuted).isEqualTo(11);var db=operator(url);
        for(String table:List.of("users","organizations","workflows","workflow_executions","approval_policy_versions","organization_approval_policies","approval_requests","approval_decisions","approval_signals","approval_signal_receipts","approval_events"))
            assertThat(db.queryForObject("SELECT count(*) FROM "+table,Long.class)).isZero();
        for(String table:List.of("approval_policy_versions","approval_decisions","approval_signals","approval_signal_receipts","approval_invalidations","approval_events"))for(String permission:List.of("UPDATE","DELETE","TRUNCATE"))
            assertThat(db.queryForObject("SELECT has_table_privilege('bridgeops_app',?,?)",Boolean.class,table,permission)).isFalse();
        for(String column:List.of("intent_hash","arguments_json","requested_by","policy_version","expires_at"))
            assertThat(db.queryForObject("SELECT has_column_privilege('bridgeops_app','approval_requests',?,'UPDATE')",Boolean.class,column)).isFalse();
        var runtime=new JdbcTemplate(new DriverManagerDataSource(url,"bridgeops_app","synthetic-runtime-fixture"));
        assertThatThrownBy(()->runtime.update("UPDATE approval_requests SET intent_hash=repeat('a',64)")).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(()->runtime.update("DELETE FROM approval_decisions")).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(()->runtime.update("UPDATE approval_signal_receipts SET consumed_at=now()")).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(db.queryForObject("SELECT bootstrap_consumed_at IS NULL FROM installation_state",Boolean.class)).isTrue();
    }
    @Test void v10UpgradePreservesAccountTenantDefinitionsExecutionAttemptsTimelineAndCommittedReceipt(){
        String url=database();migration(url,"10").migrate();var db=operator(url);Instant now=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);var timestamp=java.sql.Timestamp.from(now);UUID user=UUID.randomUUID();
        db.update("INSERT INTO users(id,email,normalized_email,password_hash,display_name,account_status,created_at,email_verified_at) VALUES(?,'upgrade@example.invalid','upgrade@example.invalid',?,'Synthetic','ACTIVE',?,?)",user,new AccountPasswords().hash("disposable-upgrade-password"),timestamp,timestamp);
        db.update("UPDATE installation_state SET bootstrap_consumed_at=?,initial_user_id=? WHERE singleton_id=1",timestamp,user);
        UUID org=new OrganizationStore(db).insert(user,"Preserved","preserved-v10",now).id();var workflows=new WorkflowStore(db);var graphs=new GraphDefinition();
        String graph="{\"schemaVersion\":1,\"nodes\":[{\"id\":\"start\",\"type\":\"manualTrigger\",\"typeVersion\":1,\"config\":{}},{\"id\":\"action\",\"type\":\"syntheticAction\",\"typeVersion\":1,\"config\":{\"operation\":\"record\",\"message\":\"Preserved record\"}},{\"id\":\"end\",\"type\":\"end\",\"typeVersion\":1,\"config\":{}}],\"edges\":[{\"source\":\"start\",\"target\":\"action\"},{\"source\":\"action\",\"target\":\"end\"}]}";
        UUID workflow=workflows.create(org,user,"Saved","",graph,now);var canonical=graphs.canonical(graphs.parse(graph));UUID version=workflows.publish(workflows.get(org,workflow,false),user,canonical.json(),canonical.hash(),now);workflows.point(workflows.get(org,workflow,false),version,now);
        UUID execution=UUID.randomUUID(),operation=UUID.randomUUID();var store=new ExecutionStore(db);String hash="a".repeat(64);
        store.insert(execution,org,workflow,version,canonical.hash(),user,"MANUAL","upgrade-request",hash,null,now,now);
        store.insertStep(org,execution,"action",0,"syntheticAction",operation,hash);var e=store.get(org,execution,false);store.state(e,ExecutionState.RUNNING,null,now);
        db.update("UPDATE execution_steps SET status='SUCCEEDED',attempt_count=1,attempt_started_at=?,completed_at=?,output_json='{\"recorded\":true}' WHERE organization_id=? AND execution_id=?",timestamp,timestamp,org,execution);
        db.update("INSERT INTO execution_attempts(id,organization_id,execution_id,node_id,attempt_number,started_at,completed_at,outcome) VALUES(?,?,?,'action',1,?,?,'SUCCEEDED')",UUID.randomUUID(),org,execution,timestamp,timestamp);
        db.update("INSERT INTO synthetic_records VALUES(?,?,?,?)",org,operation,"Preserved record",timestamp);
        db.update("INSERT INTO execution_operation_receipts VALUES(?,?,'synthetic.record','action',?,'{\"recorded\":true}',?)",org,operation,hash,timestamp);
        e=store.get(org,execution,false);store.event(e,"RECORD_COMMITTED","action",now);store.state(e,ExecutionState.SUCCEEDED,null,now);store.done(e);
        var tables=List.of("users","installation_state","organizations","organization_memberships","membership_roles","role_permissions","workflows","workflow_drafts","workflow_versions","workflow_executions","execution_steps","execution_attempts","execution_events","execution_work_items","synthetic_records","execution_operation_receipts");
        var before=new HashMap<String,List<Map<String,Object>>>();for(String table:tables)before.put(table,db.queryForList("SELECT * FROM "+table));
        assertThat(migration(url,null).migrate().migrationsExecuted).isEqualTo(1);
        for(String table:tables)assertThat(db.queryForList("SELECT * FROM "+table)).containsExactlyInAnyOrderElementsOf(before.get(table));
        assertThat(db.queryForObject("SELECT max(version::int) FROM flyway_schema_history WHERE success",Integer.class)).isEqualTo(11);
        assertThat(db.queryForObject("SELECT count(*) FROM approval_requests",Long.class)).isZero();
        assertThatThrownBy(()->db.update("UPDATE execution_operation_receipts SET result_json='{}'")).isInstanceOf(org.springframework.dao.DataAccessException.class);
    }
}
