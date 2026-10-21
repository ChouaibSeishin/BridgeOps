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
class OutboundMigrationIT {
    @Container static final PostgreSQLContainer postgres=new PostgreSQLContainer(DockerImageName.parse("postgres@sha256:77f585114c32fbca283dc835b0596f4e52b51b4c6662d7810b2f4084f60a1873").asCompatibleSubstituteFor("postgres")).withInitScript("organization-runtime.sql");
    String database(){String name="outbound_"+UUID.randomUUID().toString().replace("-","");new JdbcTemplate(new DriverManagerDataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword())).execute("CREATE DATABASE "+name);return postgres.getJdbcUrl().replace("/"+postgres.getDatabaseName(),"/"+name);}
    Flyway migration(String url,String target){return Flyway.configure().dataSource(url,postgres.getUsername(),postgres.getPassword()).locations("classpath:db/migration").target(target).load();}
    JdbcTemplate operator(String url){return new JdbcTemplate(new DriverManagerDataSource(url,postgres.getUsername(),postgres.getPassword()));}
    @Test void freshV13CreatesNoConnectionsAndRestrictsLeaseColumns(){String url=database();assertThat(migration(url,"13").migrate().migrationsExecuted).isEqualTo(13);var db=operator(url);assertThat(db.queryForObject("SELECT count(*) FROM integration_connections",Long.class)).isZero();assertThat(db.queryForObject("SELECT count(*) FROM integration_outbound_slots",Long.class)).isZero();for(String column:List.of("id","organization_id","connection_id","acquired_at","lease_expires_at"))assertThat(db.queryForObject("SELECT has_column_privilege('bridgeops_app','integration_outbound_slots',?,'UPDATE')",Boolean.class,column)).isFalse();assertThat(db.queryForObject("SELECT has_column_privilege('bridgeops_app','integration_outbound_slots','released_at','UPDATE')",Boolean.class)).isTrue();}
    @Test void v12UpgradePreservesExistingRowFingerprints(){String url=database();migration(url,"12").migrate();var db=operator(url);Map<String,String> before=fingerprints(db);assertThat(migration(url,"13").migrate().migrationsExecuted).isEqualTo(1);assertThat(fingerprints(db).entrySet()).containsAll(before.entrySet());}
    Map<String,String> fingerprints(JdbcTemplate db){var result=new TreeMap<String,String>();for(String table:db.queryForList("SELECT tablename FROM pg_tables WHERE schemaname='public' AND tablename<>'flyway_schema_history' ORDER BY tablename",String.class))result.put(table,db.queryForObject("SELECT md5(coalesce(string_agg(j::text,',' ORDER BY j::text),'')) FROM (SELECT to_jsonb(t) j FROM "+table+" t) s",String.class));return result;}
}
