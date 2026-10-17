package com.bridgeops.workflows;

import com.bridgeops.identity.application.*;
import com.bridgeops.identity.domain.SessionOutcome.Granted;
import com.bridgeops.organizations.application.OrganizationService;
import com.bridgeops.support.TestAuthMaterial;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"bridgeops.auth.enabled=true","bridgeops.auth.origin=https://bridgeops.invalid","bridgeops.auth.issuer=workflow-http","bridgeops.auth.key-id=workflow-http","bridgeops.api-docs.enabled=true"})
@AutoConfigureMockMvc(print=MockMvcPrint.NONE)
@Testcontainers
class WorkflowHttpIT {
    static final String ORIGIN = "https://bridgeops.invalid"; static final TestAuthMaterial keys = new TestAuthMaterial();
    @Container static final PostgreSQLContainer postgres = new PostgreSQLContainer(DockerImageName.parse("postgres@sha256:77f585114c32fbca283dc835b0596f4e52b51b4c6662d7810b2f4084f60a1873").asCompatibleSubstituteFor("postgres")).withInitScript("organization-runtime.sql");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", postgres::getJdbcUrl); r.add("spring.datasource.username", () -> "bridgeops_app"); r.add("spring.datasource.password", () -> "synthetic-runtime-fixture"); r.add("spring.flyway.user", postgres::getUsername); r.add("spring.flyway.password", postgres::getPassword);
        r.add("bridgeops.auth.private-key", () -> keys.directory.resolve("private.pem").toString()); r.add("bridgeops.auth.public-key", () -> keys.directory.resolve("public.pem").toString()); r.add("bridgeops.auth.csrf-key", () -> keys.directory.resolve("csrf.key").toString());
    }
    @Autowired MockMvc mvc; @Autowired AccountPasswords passwords; @Autowired PasswordSessionService sessions; @Autowired AccessTokens tokens; @Autowired OrganizationService organizations;
    final ObjectMapper json = new ObjectMapper(); JdbcTemplate operator; Browser owner; UUID organization; static String hash;
    record Browser(UUID user, UUID family, Cookie refresh, Cookie csrf, String access) { @Override public String toString() { return "Browser[REDACTED]"; } }
    @BeforeEach void fixture() throws Exception {
        operator = new JdbcTemplate(new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
        operator.execute("TRUNCATE users,installation_state,authentication_throttles CASCADE"); operator.update("INSERT INTO installation_state(singleton_id) VALUES(1)");
        if (hash == null) hash = passwords.hash("workflow-http-disposable"); owner = account("owner@example.invalid");
        operator.update("INSERT INTO user_grants VALUES(?,'organization.create',?)", owner.user(), java.sql.Timestamp.from(Instant.now()));
        organization = organizations.create(owner.user(), owner.family(), "HTTP workflow", "http-workflow").id();
    }
    Browser account(String email) throws Exception {
        UUID id = UUID.randomUUID(); var now = java.sql.Timestamp.from(Instant.now());
        operator.update("INSERT INTO users(id,email,normalized_email,password_hash,display_name,account_status,created_at,email_verified_at) VALUES(?,?,?,?,?,'ACTIVE',?,?)", id, email, email, hash, "HTTP fixture", now, now);
        var grant = (Granted) sessions.authenticate(email, "workflow-http-disposable"); var refresh = new Cookie("__Host-bridgeops-refresh", grant.refreshToken());
        var csrf = mvc.perform(get("/api/v1/auth/csrf").cookie(refresh)).andExpect(status().isOk()).andReturn().getResponse().getCookie("__Host-bridgeops-csrf");
        return new Browser(id, grant.familyId(), refresh, csrf, tokens.issue(grant).value());
    }
    Browser viewer() throws Exception {
        var browser = account("viewer@example.invalid"); UUID member = UUID.randomUUID();
        operator.update("INSERT INTO organization_memberships(id,organization_id,user_id,status,joined_at) VALUES(?,?,?,'ACTIVE',now())", member, organization, browser.user());
        operator.update("INSERT INTO membership_roles SELECT ?,?,id FROM roles WHERE organization_id=? AND name='VIEWER'", organization, member, organization); return browser;
    }
    String base() { return "/api/v1/organizations/" + organization + "/workflows"; }
    ResultActions send(Browser browser, MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request.header("Authorization", "Bearer " + browser.access()).header("Origin", ORIGIN).cookie(browser.refresh(), browser.csrf()).header("X-CSRF-TOKEN", browser.csrf().getValue()).contentType("application/json"));
    }
    String body(Object value) { return json.writeValueAsString(value); }
    JsonNode read(MvcResult result) throws Exception { return json.readTree(result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8)); }
    JsonNode create() throws Exception { return read(send(owner, post(base()).content(body(Map.of("name", "Triage", "description", "Definition only", "definition", json.readTree(GraphDefinitionTest.GRAPH))))).andExpect(status().isCreated()).andExpect(header().exists("Location")).andReturn()); }
    String path(JsonNode draft) { return base() + "/" + draft.path("workflow").path("id").asString(); }
    String revision(long value) { return body(Map.of("expectedRevision", value)); }
    String save(long revision, String graph) { return body(Map.of("expectedRevision", revision, "name", "Edited", "description", "Updated", "definition", json.readTree(graph))); }

    @Test void createSaveReloadValidatePublishHistoryAndExactSnapshotUseRealContracts() throws Exception {
        String path = path(create());
        send(owner, patch(path).content(save(0, GraphDefinitionTest.GRAPH))).andExpect(status().isOk()).andExpect(jsonPath("$.workflow.revision").value(1));
        send(owner, get(path)).andExpect(status().isOk()).andExpect(jsonPath("$.definition.nodes[1].config.message").value("Prepare review"));
        send(owner, post(path + "/validate").content(revision(1))).andExpect(status().isOk()).andExpect(jsonPath("$.valid").value(true)).andExpect(jsonPath("$.errors").isEmpty());
        var published = send(owner, post(path + "/publish").content(revision(1))).andExpect(status().isCreated()).andExpect(header().exists("Location")).andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store"))).andExpect(jsonPath("$.version.versionNumber").value(1)).andReturn();
        String exact = published.getResponse().getHeader("Location"); JsonNode first = read(published);
        send(owner, get(path + "/versions?size=1")).andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1)).andExpect(jsonPath("$.items[0].definition").doesNotExist()).andExpect(jsonPath("$.items[0].definitionHash").value(first.path("version").path("definitionHash").asString()));
        send(owner, patch(path).content(save(2, GraphDefinitionTest.GRAPH.replace("Prepare review", "Later edit")))).andExpect(status().isOk());
        assertThat(read(send(owner, get(exact)).andExpect(status().isOk()).andReturn())).isEqualTo(first);
        send(owner, get(base() + "?size=1")).andExpect(status().isOk()).andExpect(jsonPath("$.items[0].revision").value(3)).andExpect(jsonPath("$.items[0].definition").doesNotExist());
    }
    @Test void invalidSavedDefinitionReturnsSafeStructured422WithoutPublishing() throws Exception {
        String path = path(create()); String graph = GraphDefinitionTest.GRAPH.replace("\"message\":\"Prepare review\"", "\"secretInput\":\"synthetic-canary-content\"");
        send(owner, patch(path).content(save(0, graph))).andExpect(status().isOk());
        send(owner, post(path + "/validate").content(revision(1))).andExpect(status().isOk()).andExpect(jsonPath("$.valid").value(false));
        var response = send(owner, post(path + "/publish").content(revision(1))).andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("INVALID_WORKFLOW")).andExpect(jsonPath("$.details.valid").value(false)).andExpect(jsonPath("$.details.errors[0].nodeId").value("prepare")).andExpect(jsonPath("$.correlationId").isNotEmpty()).andReturn().getResponse().getContentAsString();
        assertThat(response).doesNotContain("synthetic-canary-content", "secretInput", "Exception", "com.bridgeops");
        send(owner, get(path + "/versions")).andExpect(jsonPath("$.totalElements").value(0)); send(owner, get(path)).andExpect(jsonPath("$.workflow.revision").value(1));
    }
    @Test void staleRevisionNeverOverwritesPublishesDuplicatesOrArchives() throws Exception {
        String path = path(create()); send(owner, patch(path).content(save(0, GraphDefinitionTest.GRAPH))).andExpect(status().isOk());
        send(owner, patch(path).content(save(0, "{}"))).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("STALE_REVISION"));
        for (String action : List.of("validate", "publish", "duplicate")) send(owner, post(path + "/" + action).content(revision(0))).andExpect(status().isConflict());
        send(owner, delete(path).header("If-Match", "\"0\"")).andExpect(status().isConflict());
        send(owner, get(path)).andExpect(jsonPath("$.workflow.revision").value(1));
    }
    @Test void duplicateAndArchiveHaveExplicitRevisionAndPreserveVersionReads() throws Exception {
        String path = path(create()); var published = send(owner, post(path + "/publish").content(revision(0))).andExpect(status().isCreated()).andReturn();
        String exact = published.getResponse().getHeader("Location");
        var copy = send(owner, post(path + "/duplicate").content(revision(1))).andExpect(status().isCreated()).andExpect(jsonPath("$.workflow.revision").value(0)).andExpect(jsonPath("$.currentPublishedVersion").isEmpty()).andReturn();
        assertThat(copy.getResponse().getHeader("Location")).isNotEqualTo(path);
        send(owner, delete(path)).andExpect(status().isBadRequest()); send(owner, delete(path).header("If-Match", "1")).andExpect(status().isBadRequest());
        send(owner, delete(path).header("If-Match", "\"1\"")).andExpect(status().isNoContent());
        send(owner, get(path)).andExpect(jsonPath("$.workflow.status").value("ARCHIVED")); send(owner, get(exact)).andExpect(status().isOk());
        send(owner, post(path + "/publish").content(revision(2))).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("WORKFLOW_ARCHIVED"));
    }
    @Test void authenticationOriginCsrfAndCurrentFamilyAreRequired() throws Exception {
        mvc.perform(get(base())).andExpect(status().isUnauthorized());
        var payload = body(Map.of("name", "No", "description", "", "definition", json.readTree("{}")));
        mvc.perform(post(base()).header("Authorization", "Bearer " + owner.access()).contentType("application/json").content(payload)).andExpect(status().isForbidden());
        mvc.perform(post(base()).header("Authorization", "Bearer " + owner.access()).header("Origin", "https://hostile.invalid").cookie(owner.csrf(), owner.refresh()).header("X-CSRF-TOKEN", owner.csrf().getValue()).contentType("application/json").content(payload)).andExpect(status().isForbidden());
        sessions.revokeAll(owner.user()); send(owner, get(base())).andExpect(status().isUnauthorized());
    }
    @Test void readersCannotMutateAndPermissionsAreAlwaysLive() throws Exception {
        String path = path(create()); Browser viewer = viewer(); send(viewer, get(path)).andExpect(status().isOk());
        send(viewer, post(base()).content(body(Map.of("name", "No", "description", "", "definition", json.readTree("{}"))))).andExpect(status().isForbidden());
        send(viewer, patch(path).content(save(0, "{}"))).andExpect(status().isForbidden());
        for (String action : List.of("publish", "duplicate")) send(viewer, post(path + "/" + action).content(revision(0))).andExpect(status().isForbidden());
        send(viewer, delete(path).header("If-Match", "\"0\"")).andExpect(status().isForbidden());
        send(viewer, post(path + "/validate").content(revision(0))).andExpect(status().isOk());
        operator.update("DELETE FROM role_permissions WHERE organization_id=? AND permission_code='workflow.read'", organization); send(owner, get(path)).andExpect(status().isForbidden());
    }
    @Test void everyResourceFamilyConcealsAnotherTenant() throws Exception {
        String path = path(create()); var published = send(owner, post(path + "/publish").content(revision(0))).andExpect(status().isCreated()).andReturn(); Browser outsider = account("outside@example.invalid");
        for (String route : List.of(base(), path, path + "/versions", published.getResponse().getHeader("Location"))) send(outsider, get(route)).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
        send(outsider, patch(path).content(save(1, "{}"))).andExpect(status().isNotFound());
        send(outsider, delete(path).header("If-Match", "\"1\"")).andExpect(status().isNotFound());
        for (String action : List.of("validate", "publish", "duplicate")) send(outsider, post(path + "/" + action).content(revision(1))).andExpect(status().isNotFound());
        UUID other = organizations.create(owner.user(), owner.family(), "Other", "other-http").id(); String foreign = path.replace(organization.toString(), other.toString());
        send(owner, get(foreign)).andExpect(status().isNotFound()); send(owner, post(foreign + "/publish").content(revision(1))).andExpect(status().isNotFound());
    }
    @Test void unknownDtoFieldsDuplicateKeysAndRequestLimitsAreRejectedBeforePersistence() throws Exception {
        for (String invalid : List.of("{\"name\":\"No\",\"description\":\"\",\"definition\":{},\"organizationId\":\"forged\"}", "{\"name\":\"first\",\"name\":\"second\",\"description\":\"\",\"definition\":{}}", "{\"name\":\"No\",\"description\":\"\",\"definition\":" + "[".repeat(40) + "0" + "]".repeat(40) + "}"))
            send(owner, post(base()).content(invalid)).andExpect(status().isBadRequest());
        send(owner, post(base()).content(" ".repeat(256 * 1024 + 1))).andExpect(status().isContentTooLarge());
        var graph = json.createObjectNode(); var nodes = json.createArrayNode(); for (int i = 0; i < 51; i++) nodes.add(json.createObjectNode()); graph.set("nodes", nodes);
        send(owner, post(base()).content(body(Map.of("name", "Too many", "description", "", "definition", graph)))).andExpect(status().isContentTooLarge());
        assertThat(operator.queryForObject("SELECT count(*) FROM workflows", Long.class)).isZero();
        send(owner, get(base() + "?size=101")).andExpect(status().isBadRequest());
    }
    @Test void metadataAndRevisionsRejectScalarCoercionWithoutPublishing() throws Exception {
        String path = path(create());
        for (String value : List.of("0.5", "\"0\"", "null", "-1", "9223372036854775808"))
            send(owner, post(path + "/publish").content("{\"expectedRevision\":" + value + "}")).andExpect(status().isBadRequest());
        for (String invalid : List.of("{\"name\":true,\"description\":\"\",\"definition\":{}}", "{\"name\":\"No\",\"description\":42,\"definition\":{}}"))
            send(owner, post(base()).content(invalid)).andExpect(status().isBadRequest());
        send(owner, get(path)).andExpect(jsonPath("$.workflow.revision").value(0));
        assertThat(operator.queryForObject("SELECT count(*) FROM workflow_versions", Long.class)).isZero();
    }
    @Test void requiredAuditFailureIsGeneric503AndPublicationRollsBack() throws Exception {
        String path = path(create()); operator.execute("CREATE FUNCTION reject_http_publication() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.action='WORKFLOW_PUBLISHED' THEN RAISE EXCEPTION 'synthetic provider detail'; END IF; RETURN NEW; END $$");
        operator.execute("CREATE TRIGGER reject_http_publication BEFORE INSERT ON organization_audit_events FOR EACH ROW EXECUTE FUNCTION reject_http_publication()");
        try {
            String response = send(owner, post(path + "/publish").content(revision(0))).andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("DEPENDENCY_UNAVAILABLE")).andReturn().getResponse().getContentAsString();
            assertThat(response).doesNotContain("synthetic provider detail", "SQLException", "postgres");
        } finally { operator.execute("DROP TRIGGER reject_http_publication ON organization_audit_events"); operator.execute("DROP FUNCTION reject_http_publication()"); }
        send(owner, get(path)).andExpect(jsonPath("$.workflow.revision").value(0)).andExpect(jsonPath("$.currentPublishedVersion").isEmpty());
        send(owner, get(path + "/versions")).andExpect(jsonPath("$.totalElements").value(0));
    }
    @Test void openApiRetainsDefinitionRoutesAndUnpublishedExecutionCannotRun() throws Exception {
        mvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andExpect(jsonPath("$.paths['/api/v1/organizations/{organization}/workflows'].post").exists()).andExpect(jsonPath("$.paths['/api/v1/organizations/{organization}/workflows/{id}/versions/{version}'].get").exists());
        String path = path(create()); for (String action : List.of("test-runs", "approve")) send(owner, post(path + "/" + action).content("{}")).andExpect(status().isForbidden());
        send(owner, post(path + "/executions").header("Idempotency-Key","unpublished-workflow-key").content("{\"input\":{}}")).andExpect(status().isUnprocessableContent());
        assertThat(operator.queryForObject("SELECT count(*) FROM workflow_executions",Long.class)).isZero();
        assertThat(operator.queryForObject("SELECT count(*) FROM information_schema.tables WHERE table_schema='public' AND table_name IN ('approval_requests','work_items')", Long.class)).isZero();
    }
    @AfterAll static void cleanup() throws Exception { keys.close(); }
}
