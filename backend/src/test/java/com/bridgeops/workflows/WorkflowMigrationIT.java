package com.bridgeops.workflows;

import com.bridgeops.identity.application.AccountPasswords;
import com.bridgeops.organizations.application.RoleTemplates;
import com.bridgeops.organizations.persistence.OrganizationStore;
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

/** Every case gets a new database in this disposable container. No local reset/cleanup path. */
@Testcontainers
class WorkflowMigrationIT {
    @Container static final PostgreSQLContainer postgres = new PostgreSQLContainer(DockerImageName.parse("postgres@sha256:77f585114c32fbca283dc835b0596f4e52b51b4c6662d7810b2f4084f60a1873").asCompatibleSubstituteFor("postgres")).withInitScript("organization-runtime.sql");
    String database() {
        String name = "workflow_" + UUID.randomUUID().toString().replace("-", "");
        new JdbcTemplate(new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())).execute("CREATE DATABASE " + name);
        return postgres.getJdbcUrl().replace("/" + postgres.getDatabaseName(), "/" + name);
    }
    Flyway migration(String url, String target) {
        var configuration = Flyway.configure().dataSource(url, postgres.getUsername(), postgres.getPassword()).locations("classpath:db/migration");
        if (target != null) configuration.target(target); return configuration.load();
    }
    @Test void emptyDatabaseMigratesAllNineWithoutCreatingAnAccountOrTenant() {
        String url = database(); migration(url, "9").migrate();
        var jdbc = new JdbcTemplate(new DriverManagerDataSource(url, postgres.getUsername(), postgres.getPassword()));
        assertThat(jdbc.queryForObject("SELECT max(version::int) FROM flyway_schema_history WHERE success", Integer.class)).isEqualTo(9);
        for (String table : List.of("users", "organizations", "workflows", "workflow_drafts", "workflow_versions"))
            assertThat(jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT bootstrap_consumed_at IS NULL FROM installation_state", Boolean.class)).isTrue();
    }
    @Test void upgradingV8PreservesConsumedInstallationAccountTenantAndRoleMappings() {
        String url = database(); migration(url, "8").migrate();
        var jdbc = new JdbcTemplate(new DriverManagerDataSource(url, postgres.getUsername(), postgres.getPassword()));
        UUID user = UUID.randomUUID(); var now = java.sql.Timestamp.from(Instant.now()); String hash = new AccountPasswords().hash("disposable-upgrade-password");
        jdbc.update("INSERT INTO users(id,email,normalized_email,password_hash,display_name,account_status,created_at,email_verified_at) VALUES(?,'upgrade@example.invalid','upgrade@example.invalid',?,'Upgrade fixture','ACTIVE',?,?)", user, hash, now, now);
        jdbc.update("UPDATE installation_state SET bootstrap_consumed_at=?,initial_user_id=? WHERE singleton_id=1", now, user);
        jdbc.update("INSERT INTO user_grants VALUES(?,'organization.create',?)", user, now);
        UUID organization = new OrganizationStore(jdbc).insert(user, "Existing organization", "existing-upgrade", Instant.now()).id();
        var users = jdbc.queryForList("SELECT * FROM users"); var tenants = jdbc.queryForList("SELECT * FROM organizations"); var roles = jdbc.queryForList("SELECT * FROM role_permissions ORDER BY organization_id,role_id,permission_code");
        assertThat(migration(url, "9").migrate().migrationsExecuted).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT * FROM users")).isEqualTo(users); assertThat(jdbc.queryForList("SELECT * FROM organizations")).isEqualTo(tenants);
        assertThat(jdbc.queryForList("SELECT * FROM role_permissions ORDER BY organization_id,role_id,permission_code")).isEqualTo(roles);
        assertThat(jdbc.queryForObject("SELECT initial_user_id FROM installation_state", UUID.class)).isEqualTo(user);
        assertThat(jdbc.queryForObject("SELECT bootstrap_consumed_at IS NOT NULL FROM installation_state", Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM user_grants WHERE user_id=?", Long.class, user)).isEqualTo(1);
        for (var role : RoleTemplates.ALL.entrySet()) {
            var actual = jdbc.queryForList("SELECT p.permission_code FROM role_permissions p JOIN roles r ON r.organization_id=p.organization_id AND r.id=p.role_id WHERE r.organization_id=? AND r.name=?", String.class, organization, role.getKey());
            assertThat(actual).containsExactlyInAnyOrderElementsOf(role.getValue());
        }
        assertThat(jdbc.queryForObject("SELECT has_table_privilege('bridgeops_app','workflow_versions','UPDATE')", Boolean.class)).isFalse();
        assertThat(jdbc.queryForObject("SELECT has_table_privilege('bridgeops_app','workflow_versions','DELETE')", Boolean.class)).isFalse();
        assertThat(jdbc.queryForObject("SELECT has_table_privilege('bridgeops_app','users','INSERT')", Boolean.class)).isFalse();
    }
}
