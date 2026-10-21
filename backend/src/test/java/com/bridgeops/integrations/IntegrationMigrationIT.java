package com.bridgeops.integrations;

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
class IntegrationMigrationIT {
    @Container static final PostgreSQLContainer postgres=new PostgreSQLContainer(DockerImageName.parse("postgres@sha256:77f585114c32fbca283dc835b0596f4e52b51b4c6662d7810b2f4084f60a1873").asCompatibleSubstituteFor("postgres")).withInitScript("organization-runtime.sql");
    String database(){String name="integration_"+UUID.randomUUID().toString().replace("-","");new JdbcTemplate(new DriverManagerDataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword())).execute("CREATE DATABASE "+name);return postgres.getJdbcUrl().replace("/"+postgres.getDatabaseName(),"/"+name);}
    Flyway migration(String url,String target){var config=Flyway.configure().dataSource(url,postgres.getUsername(),postgres.getPassword()).locations("classpath:db/migration");if(target!=null)config.target(target);return config.load();}
    JdbcTemplate database(String url){return new JdbcTemplate(new DriverManagerDataSource(url,postgres.getUsername(),postgres.getPassword()));}
    @Test void freshSchemaCreatesEmptyBusinessStateAndRestrictsAppendOnlyPrivileges(){String url=database();assertThat(migration(url,null).migrate().migrationsExecuted).isEqualTo(12);var db=database(url);for(String table:List.of("integration_connections","integration_secrets","support_tickets","support_teams","internal_notifications","integration_demo_installations"))assertThat(db.queryForObject("SELECT count(*) FROM "+table,Long.class)).isZero();for(String table:List.of("integration_secrets","integration_connection_versions","integration_operation_receipts","integration_batch_items","internal_notification_attempts"))for(String permission:List.of("UPDATE","DELETE","TRUNCATE"))assertThat(db.queryForObject("SELECT has_table_privilege('bridgeops_app',?,?)",Boolean.class,table,permission)).isFalse();assertThat(db.queryForObject("SELECT has_column_privilege('bridgeops_app','support_tickets','title','UPDATE')",Boolean.class)).isFalse();}
    @Test void v11UpgradePreservesAllExistingTableRowsAndGrants(){String url=database();migration(url,"11").migrate();var db=database(url);UUID user=UUID.randomUUID();db.update("INSERT INTO users(id,email,normalized_email,password_hash,display_name,account_status,created_at,email_verified_at) VALUES(?,'upgrade@example.invalid','upgrade@example.invalid',?,'Preserved','ACTIVE',now(),now())",user,"$2a$12$disposable-not-an-authentication-fixture");db.update("UPDATE installation_state SET bootstrap_consumed_at=now(),initial_user_id=?",user);var org=new com.bridgeops.organizations.persistence.OrganizationStore(db).insert(user,"Preserved","preserved",java.time.Instant.now());var before=fingerprints(db);assertThat(migration(url,null).migrate().migrationsExecuted).isEqualTo(1);assertThat(fingerprints(db).entrySet()).containsAll(before.entrySet());assertThat(db.queryForObject("SELECT bootstrap_consumed_at IS NOT NULL FROM installation_state",Boolean.class)).isTrue();assertThat(db.queryForObject("SELECT count(*) FROM organizations WHERE id=?",Long.class,org.id())).isEqualTo(1);}
    Map<String,String> fingerprints(JdbcTemplate db){var result=new TreeMap<String,String>();for(String table:db.queryForList("SELECT tablename FROM pg_tables WHERE schemaname='public' AND tablename<>'flyway_schema_history' ORDER BY tablename",String.class))result.put(table,db.queryForObject("SELECT md5(coalesce(string_agg(j::text,',' ORDER BY j::text),'')) FROM (SELECT to_jsonb(t) j FROM "+table+" t) s",String.class));return result;}
}
