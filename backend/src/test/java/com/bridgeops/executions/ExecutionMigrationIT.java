package com.bridgeops.executions;

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

@Testcontainers
class ExecutionMigrationIT {
    @Container static final PostgreSQLContainer postgres = new PostgreSQLContainer(DockerImageName.parse("postgres@sha256:77f585114c32fbca283dc835b0596f4e52b51b4c6662d7810b2f4084f60a1873").asCompatibleSubstituteFor("postgres")).withInitScript("organization-runtime.sql");
    String database() {
        String name="execution_"+UUID.randomUUID().toString().replace("-","");
        new JdbcTemplate(new DriverManagerDataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword())).execute("CREATE DATABASE "+name);
        return postgres.getJdbcUrl().replace("/"+postgres.getDatabaseName(),"/"+name);
    }
    Flyway migrate(String url,String target) {
        var config=Flyway.configure().dataSource(url,postgres.getUsername(),postgres.getPassword()).locations("classpath:db/migration");
        if(target!=null)config.target(target);return config.load();
    }
    JdbcTemplate operator(String url){return new JdbcTemplate(new DriverManagerDataSource(url,postgres.getUsername(),postgres.getPassword()));}
    @Test void freshSchemaHasRestrictedRuntimeAndNoProvisionedIdentityOrExecution() {
        String url=database();migrate(url,"10").migrate();var db=operator(url);
        assertThat(db.queryForObject("SELECT max(version::int) FROM flyway_schema_history WHERE success",Integer.class)).isEqualTo(10);
        for(String table:List.of("users","organizations","workflows","workflow_executions","execution_work_items","synthetic_records"))assertThat(db.queryForObject("SELECT count(*) FROM "+table,Long.class)).isZero();
        assertThat(db.queryForObject("SELECT bootstrap_consumed_at IS NULL FROM installation_state",Boolean.class)).isTrue();
        for(String table:List.of("execution_attempts","execution_events","synthetic_records","execution_operation_receipts","workflow_versions"))
            for(String privilege:List.of("UPDATE","DELETE","TRUNCATE"))assertThat(db.queryForObject("SELECT has_table_privilege('bridgeops_app',?,?)",Boolean.class,table,privilege)).isFalse();
        var runtime=new JdbcTemplate(new DriverManagerDataSource(url,"bridgeops_app","synthetic-runtime-fixture"));
        assertThatThrownBy(()->runtime.update("UPDATE execution_operation_receipts SET result_json='{}'" )).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(()->runtime.update("UPDATE workflow_executions SET definition_hash=repeat('a',64)")).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(db.queryForObject("SELECT has_table_privilege('bridgeops_app','users','INSERT')",Boolean.class)).isFalse();
    }
    @Test void v9UpgradePreservesExistingAccountConsumedMarkerTenantRolesDraftAndPublishedBytes() {
        String url=database();migrate(url,"9").migrate();var db=operator(url);var now=Instant.now();UUID user=UUID.randomUUID();
        db.update("INSERT INTO users(id,email,normalized_email,password_hash,display_name,account_status,created_at,email_verified_at) VALUES(?,'migration@example.invalid','migration@example.invalid',?,'Synthetic','ACTIVE',?,?)",user,new AccountPasswords().hash("disposable-migration-password"),java.sql.Timestamp.from(now),java.sql.Timestamp.from(now));
        db.update("UPDATE installation_state SET bootstrap_consumed_at=?,initial_user_id=? WHERE singleton_id=1",java.sql.Timestamp.from(now),user);
        UUID org=new OrganizationStore(db).insert(user,"Existing","existing-v9",now).id();var store=new WorkflowStore(db);var graph=new GraphDefinition();
        String json="{\"schemaVersion\":1,\"nodes\":[{\"id\":\"start\",\"type\":\"manualTrigger\",\"typeVersion\":1,\"config\":{}},{\"id\":\"end\",\"type\":\"end\",\"typeVersion\":1,\"config\":{}}],\"edges\":[{\"source\":\"start\",\"target\":\"end\"}]}";
        UUID workflow=store.create(org,user,"Saved workflow","Preserve",json,now);var canonical=graph.canonical(graph.parse(json));
        UUID version=store.publish(store.get(org,workflow,false),user,canonical.json(),canonical.hash(),now);store.point(store.get(org,workflow,false),version,now);
        var tables=List.of("users","installation_state","organizations","role_permissions","workflows","workflow_drafts","workflow_versions");
        Map<String,List<Map<String,Object>>> before=new HashMap<>();for(String table:tables)before.put(table,db.queryForList("SELECT * FROM "+table));
        assertThat(migrate(url,"10").migrate().migrationsExecuted).isEqualTo(1);
        for(String table:tables)assertThat(db.queryForList("SELECT * FROM "+table)).containsExactlyInAnyOrderElementsOf(before.get(table));
        assertThat(db.queryForObject("SELECT count(*) FROM workflow_executions",Long.class)).isZero();
        assertThat(db.queryForObject("SELECT bootstrap_consumed_at IS NOT NULL FROM installation_state",Boolean.class)).isTrue();
    }
}
