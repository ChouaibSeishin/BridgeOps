package com.bridgeops.workflows;

import com.bridgeops.identity.application.*;
import com.bridgeops.identity.domain.SessionOutcome.Granted;
import com.bridgeops.organizations.application.OrganizationService;
import com.bridgeops.shared.web.DomainFailure;
import com.bridgeops.support.TestAuthMaterial;
import com.bridgeops.workflows.application.*;
import com.bridgeops.workflows.application.WorkflowViews.*;
import com.bridgeops.workflows.domain.GraphDefinition;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties={"bridgeops.auth.enabled=true","bridgeops.auth.origin=https://bridgeops.invalid","bridgeops.auth.issuer=workflow-test","bridgeops.auth.key-id=workflow-test"})
@Testcontainers
class WorkflowLifecycleIT {
    static final TestAuthMaterial keys = new TestAuthMaterial();
    @Container static final PostgreSQLContainer postgres = new PostgreSQLContainer(DockerImageName.parse("postgres@sha256:77f585114c32fbca283dc835b0596f4e52b51b4c6662d7810b2f4084f60a1873").asCompatibleSubstituteFor("postgres")).withInitScript("organization-runtime.sql");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", postgres::getJdbcUrl); r.add("spring.datasource.username", () -> "bridgeops_app"); r.add("spring.datasource.password", () -> "synthetic-runtime-fixture");
        r.add("spring.flyway.user", postgres::getUsername); r.add("spring.flyway.password", postgres::getPassword);
        r.add("bridgeops.auth.private-key", () -> keys.directory.resolve("private.pem").toString()); r.add("bridgeops.auth.public-key", () -> keys.directory.resolve("public.pem").toString()); r.add("bridgeops.auth.csrf-key", () -> keys.directory.resolve("csrf.key").toString());
    }
    @Autowired WorkflowService workflows; @Autowired GraphDefinition graphs; @Autowired OrganizationService organizations;
    @Autowired AccountPasswords passwords; @Autowired PasswordSessionService sessions; @Autowired JdbcTemplate runtime;
    JdbcTemplate operator; Actor owner; UUID organization; static String passwordHash;
    record Actor(UUID id, UUID family) {}
    @BeforeEach void fixtures() {
        operator = new JdbcTemplate(new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
        // This connection is solely the disposable Testcontainer operator, never local Compose.
        operator.execute("TRUNCATE users,installation_state,authentication_throttles CASCADE");
        operator.update("INSERT INTO installation_state(singleton_id) VALUES(1)");
        if (passwordHash == null) passwordHash = passwords.hash("workflow-disposable-password");
        owner = account("owner@example.invalid");
        operator.update("INSERT INTO user_grants VALUES(?,'organization.create',?)", owner.id(), java.sql.Timestamp.from(Instant.now()));
        organization = organizations.create(owner.id(), owner.family(), "Workflow test", "workflow-test").id();
    }
    Actor account(String email) {
        UUID id = UUID.randomUUID(); var now = java.sql.Timestamp.from(Instant.now());
        operator.update("INSERT INTO users(id,email,normalized_email,password_hash,display_name,account_status,created_at,email_verified_at) VALUES(?,?,?,?,?,'ACTIVE',?,?)", id, email, email, passwordHash, "Synthetic account", now, now);
        var grant = (Granted) sessions.authenticate(email, "workflow-disposable-password"); return new Actor(id, grant.familyId());
    }
    Actor member(String role) {
        Actor actor = account(role.toLowerCase(Locale.ROOT) + "@example.invalid"); UUID membership = UUID.randomUUID();
        operator.update("INSERT INTO organization_memberships(id,organization_id,user_id,status,joined_at) VALUES(?,?,?,'ACTIVE',?)", membership, organization, actor.id(), java.sql.Timestamp.from(Instant.now()));
        operator.update("INSERT INTO membership_roles(organization_id,membership_id,role_id) SELECT ?,?,id FROM roles WHERE organization_id=? AND name=?", organization, membership, organization, role);
        return actor;
    }
    Detail create() { return workflows.create(owner.id(), owner.family(), organization, "Ticket triage", "Definition only", graphs.parse(GraphDefinitionTest.GRAPH)); }
    Detail get(UUID id) { return workflows.get(owner.id(), owner.family(), organization, id); }
    Snapshot publish(UUID id, long revision) { return workflows.publish(owner.id(), owner.family(), organization, id, revision); }
    long count(String table) { return operator.queryForObject("SELECT count(*) FROM " + table, Long.class); }
    static void denied(int status, Runnable work) { assertThatThrownBy(work::run).isInstanceOfSatisfying(DomainFailure.class, e -> assertThat(e.status()).isEqualTo(status)); }

    @Test void restrictedRuntimeCreatesSavesReloadsAndPagesDrafts() {
        assertThat(runtime.queryForObject("SELECT current_user", String.class)).isEqualTo("bridgeops_app");
        var draft = create(); UUID id = draft.workflow().id(); assertThat(draft.workflow().revision()).isZero(); assertThat(draft.currentPublishedVersion()).isNull();
        var saved = workflows.save(owner.id(), owner.family(), organization, id, 0, "Updated name", "Changed description", graphs.parse(GraphDefinitionTest.GRAPH.replace("Prepare review", "Saved configuration")));
        assertThat(saved.workflow().revision()).isEqualTo(1); assertThat(get(id)).isEqualTo(saved);
        assertThat(saved.definition().path("nodes").get(1).path("config").path("message").asString()).isEqualTo("Saved configuration");
        var page = workflows.list(owner.id(), owner.family(), organization, 0, 1); assertThat(page.totalElements()).isEqualTo(1); assertThat(page.items().getFirst().name()).isEqualTo("Updated name");
        assertThat(workflows.list(owner.id(), owner.family(), organization, 1, 1).items()).isEmpty();
        denied(400, () -> workflows.list(owner.id(), owner.family(), organization, -1, 1)); denied(400, () -> workflows.list(owner.id(), owner.family(), organization, 0, 101));
        denied(409, () -> workflows.save(owner.id(), owner.family(), organization, id, 0, "Stale", "", graphs.parse(GraphDefinitionTest.GRAPH)));
        assertThat(get(id)).isEqualTo(saved); assertThat(count("workflow_versions")).isZero();
    }
    @Test void boundedInvalidDraftsSaveButCannotPublishOrAllocateVersion() {
        var draft = workflows.create(owner.id(), owner.family(), organization, "Incomplete", "", graphs.parse("{\"schemaVersion\":1,\"nodes\":[],\"edges\":[]}"));
        UUID id = draft.workflow().id(); assertThat(workflows.validate(owner.id(), owner.family(), organization, id, 0).valid()).isFalse();
        assertThatThrownBy(() -> publish(id, 0)).isInstanceOf(InvalidDefinition.class); assertThat(count("workflow_versions")).isZero(); assertThat(get(id).workflow().revision()).isZero();
        denied(409, () -> workflows.validate(owner.id(), owner.family(), organization, id, 99));
    }
    @Test void publicationConsumesRevisionAndFreezesDefinitionMetadataAndHash() {
        UUID id = create().workflow().id(); var first = publish(id, 0);
        assertThat(first.version().versionNumber()).isEqualTo(1); assertThat(first.version().sourceRevision()).isZero();
        assertThat(first.version().definitionHash()).isEqualTo(graphs.canonical(first.definition()).hash());
        assertThat(get(id).workflow().revision()).isEqualTo(1); assertThat(get(id).currentPublishedVersion()).isEqualTo(first.version());
        denied(409, () -> publish(id, 0));
        workflows.save(owner.id(), owner.family(), organization, id, 1, "New title", "New metadata", graphs.parse(GraphDefinitionTest.GRAPH.replace("Prepare review", "Later configuration")));
        var second = publish(id, 2); assertThat(second.version().versionNumber()).isEqualTo(2); assertThat(second.version().definitionHash()).isNotEqualTo(first.version().definitionHash());
        assertThat(workflows.version(owner.id(), owner.family(), organization, id, first.version().id())).isEqualTo(first);
        assertThat(workflows.versions(owner.id(), owner.family(), organization, id, 0, 1).items()).containsExactly(second.version());
        assertThat(workflows.versions(owner.id(), owner.family(), organization, id, 1, 1).items()).containsExactly(first.version());
        assertThat(count("workflow_versions")).isEqualTo(2);
    }
    @Test void duplicateIsIndependentAndArchiveRetainsHistory() {
        UUID id = create().workflow().id(); var snapshot = publish(id, 0);
        var copy = workflows.duplicate(owner.id(), owner.family(), organization, id, 1); UUID copyId = copy.workflow().id();
        assertThat(copyId).isNotEqualTo(id); assertThat(copy.workflow().revision()).isZero(); assertThat(copy.currentPublishedVersion()).isNull(); assertThat(copy.definition()).isEqualTo(get(id).definition());
        workflows.save(owner.id(), owner.family(), organization, copyId, 0, "Independent", "", graphs.parse("{}"));
        assertThat(get(id).definition()).isEqualTo(graphs.parse(GraphDefinitionTest.GRAPH));
        assertThat(graphs.canonical(get(id).definition())).isEqualTo(graphs.canonical(snapshot.definition()));
        workflows.archive(owner.id(), owner.family(), organization, id, 1);
        assertThat(get(id).workflow().status()).isEqualTo("ARCHIVED"); assertThat(get(id).workflow().revision()).isEqualTo(2);
        assertThat(workflows.version(owner.id(), owner.family(), organization, id, snapshot.version().id())).isEqualTo(snapshot);
        denied(409, () -> publish(id, 2)); denied(409, () -> workflows.save(owner.id(), owner.family(), organization, id, 2, "No", "", graphs.parse("{}")));
        assertThat(workflows.duplicate(owner.id(), owner.family(), organization, id, 2).workflow().status()).isEqualTo("ACTIVE");
    }
    @Test void liveRoleGrantsSeparateReaderEditorAndPublisher() {
        UUID id = create().workflow().id(); Actor viewer = member("VIEWER"), builder = member("BUILDER"), operatorActor = member("OPERATOR");
        for (Actor reader : List.of(viewer, operatorActor)) {
            assertThat(workflows.get(reader.id(), reader.family(), organization, id).workflow().id()).isEqualTo(id);
            denied(403, () -> workflows.save(reader.id(), reader.family(), organization, id, 0, "Denied", "", graphs.parse("{}")));
            denied(403, () -> workflows.publish(reader.id(), reader.family(), organization, id, 0));
        }
        operator.update("DELETE FROM role_permissions WHERE organization_id=? AND permission_code='workflow.publish' AND role_id IN (SELECT id FROM roles WHERE organization_id=? AND name='BUILDER')", organization, organization);
        workflows.save(builder.id(), builder.family(), organization, id, 0, "Editor", "", graphs.parse(GraphDefinitionTest.GRAPH));
        denied(403, () -> workflows.publish(builder.id(), builder.family(), organization, id, 1));
        assertThat(publish(id, 1).version().versionNumber()).isEqualTo(1);
        operator.update("UPDATE organization_memberships SET status='SUSPENDED' WHERE organization_id=? AND user_id=?", organization, viewer.id());
        denied(404, () -> workflows.get(viewer.id(), viewer.family(), organization, id));
        sessions.revokeAll(builder.id()); denied(401, () -> workflows.get(builder.id(), builder.family(), organization, id));
    }
    @Test void allServiceFamiliesConcealNonmemberAndForeignResources() {
        UUID id = create().workflow().id(); var published = publish(id, 0); Actor outsider = account("outside@example.invalid");
        denied(404, () -> workflows.list(outsider.id(), outsider.family(), organization, 0, 25));
        denied(404, () -> workflows.get(outsider.id(), outsider.family(), organization, id));
        denied(404, () -> workflows.validate(outsider.id(), outsider.family(), organization, id, 1));
        denied(404, () -> workflows.save(outsider.id(), outsider.family(), organization, id, 1, "No", "", graphs.parse("{}")));
        denied(404, () -> workflows.publish(outsider.id(), outsider.family(), organization, id, 1));
        denied(404, () -> workflows.duplicate(outsider.id(), outsider.family(), organization, id, 1));
        denied(404, () -> workflows.archive(outsider.id(), outsider.family(), organization, id, 1));
        denied(404, () -> workflows.versions(outsider.id(), outsider.family(), organization, id, 0, 25));
        denied(404, () -> workflows.version(outsider.id(), outsider.family(), organization, id, published.version().id()));
        UUID otherOrg = organizations.create(owner.id(), owner.family(), "Other", "other-workflow").id();
        denied(404, () -> workflows.get(owner.id(), owner.family(), otherOrg, id));
        denied(404, () -> workflows.publish(owner.id(), owner.family(), otherOrg, id, 1));
        denied(404, () -> workflows.duplicate(owner.id(), owner.family(), otherOrg, id, 1));
        denied(404, () -> workflows.version(owner.id(), owner.family(), otherOrg, id, published.version().id()));
        UUID unrelated = create().workflow().id(); denied(404, () -> workflows.version(owner.id(), owner.family(), organization, unrelated, published.version().id()));
    }
    @Test void concurrentPublicationOfOneRevisionHasOneWinner() throws Exception {
        UUID id = create().workflow().id(); Actor builder = member("BUILDER");
        var results = race(() -> { publish(id, 0); return 201; }, () -> { workflows.publish(builder.id(), builder.family(), organization, id, 0); return 201; });
        assertThat(results).containsExactlyInAnyOrder(201, 409); assertThat(count("workflow_versions")).isEqualTo(1); assertThat(get(id).workflow().revision()).isEqualTo(1);
        assertThat(operator.queryForObject("SELECT count(*) FROM organization_audit_events WHERE action='WORKFLOW_PUBLISHED'", Long.class)).isEqualTo(1);
    }
    @Test void concurrentEditAndPublicationCannotPublishAnUnintendedRevision() throws Exception {
        UUID id = create().workflow().id(); Actor builder = member("BUILDER");
        var results = race(() -> { publish(id, 0); return 201; }, () -> { workflows.save(builder.id(), builder.family(), organization, id, 0, "Concurrent edit", "", graphs.parse(GraphDefinitionTest.GRAPH.replace("Prepare review", "Changed concurrently"))); return 200; });
        assertThat(results).contains(409); assertThat(results.stream().filter(code -> code != 409)).hasSize(1); assertThat(get(id).workflow().revision()).isEqualTo(1);
        if (count("workflow_versions") == 1) assertThat(get(id).currentPublishedVersion().definitionHash()).isEqualTo(graphs.canonical(graphs.parse(GraphDefinitionTest.GRAPH)).hash());
        else assertThat(get(id).workflow().name()).isEqualTo("Concurrent edit");
    }
    @Test void requiredAuditFailureRollsBackVersionPointerRevisionAndCreate() {
        UUID id = create().workflow().id(); var before = get(id);
        operator.execute("CREATE FUNCTION fail_workflow_audit() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.resource_type='WORKFLOW' THEN RAISE EXCEPTION 'synthetic audit failure'; END IF; RETURN NEW; END $$");
        operator.execute("CREATE TRIGGER fail_workflow_audit BEFORE INSERT ON organization_audit_events FOR EACH ROW EXECUTE FUNCTION fail_workflow_audit()");
        try {
            assertThatThrownBy(() -> publish(id, 0)).isInstanceOf(org.springframework.dao.DataAccessException.class);
            assertThatThrownBy(this::create).isInstanceOf(org.springframework.dao.DataAccessException.class);
        } finally { operator.execute("DROP TRIGGER fail_workflow_audit ON organization_audit_events"); operator.execute("DROP FUNCTION fail_workflow_audit()"); }
        assertThat(get(id)).isEqualTo(before); assertThat(count("workflow_versions")).isZero(); assertThat(count("workflows")).isEqualTo(1);
    }
    @Test void databaseGrantsAndGuardsProtectPublishedRowsAndTenantPointers() {
        UUID id = create().workflow().id(); var first = publish(id, 0); UUID secondWorkflow = create().workflow().id();
        for (String sql : List.of("UPDATE workflow_versions SET definition_json='{}'", "UPDATE workflow_versions SET name='changed'", "DELETE FROM workflow_versions", "TRUNCATE workflow_versions", "DELETE FROM workflows", "UPDATE organization_audit_events SET outcome='SUCCESS'"))
            assertThatThrownBy(() -> runtime.execute(sql)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(() -> operator.update("UPDATE workflow_versions SET name='changed' WHERE id=?", first.version().id())).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> operator.update("UPDATE workflows SET current_published_version_id=? WHERE id=?", first.version().id(), secondWorkflow)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        UUID otherOrg = organizations.create(owner.id(), owner.family(), "Other", "foreign-constraints").id();
        assertThatThrownBy(() -> operator.update("INSERT INTO workflow_drafts(organization_id,workflow_id,definition_json,updated_by,updated_at) VALUES(?,?,'{}',?,now())", otherOrg, id, owner.id())).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> operator.update("INSERT INTO workflow_versions(id,organization_id,workflow_id,version_number,source_revision,schema_version,canonicalization_version,name,description,definition_json,definition_hash,published_by,published_at) VALUES(?,?,?,2,99,1,1,'No','','{}',repeat('0',64),?,now())", UUID.randomUUID(), organization, id, owner.id())).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(workflows.version(owner.id(), owner.family(), organization, id, first.version().id())).isEqualTo(first);
        assertThat(operator.queryForObject("SELECT definition_hash FROM organization_audit_events WHERE action='WORKFLOW_PUBLISHED'", String.class)).isEqualTo(first.version().definitionHash());
    }
    List<Integer> race(Supplier<Integer> first, Supplier<Integer> second) throws Exception {
        var barrier = new CyclicBarrier(2); var executor = Executors.newFixedThreadPool(2);
        try {
            var futures = List.of(first, second).stream().map(work -> executor.submit(() -> { barrier.await(5, TimeUnit.SECONDS); try { return work.get(); } catch (DomainFailure failure) { return failure.status(); } })).toList();
            return List.of(futures.get(0).get(20, TimeUnit.SECONDS), futures.get(1).get(20, TimeUnit.SECONDS));
        } finally { executor.shutdownNow(); }
    }
    @AfterAll static void cleanup() throws Exception { keys.close(); }
}
