package com.bridgeops.executions;

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

@SpringBootTest(properties={"bridgeops.auth.enabled=true","bridgeops.auth.origin=https://bridgeops.invalid","bridgeops.auth.issuer=workflow-http","bridgeops.auth.key-id=workflow-http","bridgeops.api-docs.enabled=true","bridgeops.executions.worker.enabled=false"})
@AutoConfigureMockMvc(print=MockMvcPrint.NONE)
@Testcontainers
class ExecutionHttpIT {
    static final String ORIGIN = "https://bridgeops.invalid"; static final TestAuthMaterial keys = new TestAuthMaterial();
    @Container static final PostgreSQLContainer postgres = new PostgreSQLContainer(DockerImageName.parse("postgres@sha256:77f585114c32fbca283dc835b0596f4e52b51b4c6662d7810b2f4084f60a1873").asCompatibleSubstituteFor("postgres")).withInitScript("organization-runtime.sql");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", postgres::getJdbcUrl); r.add("spring.datasource.username", () -> "bridgeops_app"); r.add("spring.datasource.password", () -> "synthetic-runtime-fixture"); r.add("spring.flyway.user", postgres::getUsername); r.add("spring.flyway.password", postgres::getPassword);
        r.add("bridgeops.auth.private-key", () -> keys.directory.resolve("private.pem").toString()); r.add("bridgeops.auth.public-key", () -> keys.directory.resolve("public.pem").toString()); r.add("bridgeops.auth.csrf-key", () -> keys.directory.resolve("csrf.key").toString());
    }
    @Autowired com.bridgeops.executions.application.ExecutionRuntime runtime;
    @Autowired com.bridgeops.executions.application.SyntheticCapability capability;
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
    JsonNode create() throws Exception { return read(send(owner, post(base()).content(body(Map.of("name", "Triage", "description", "Definition only", "definition", json.readTree(ExecutionLifecycleIT.GRAPH))))).andExpect(status().isCreated()).andExpect(header().exists("Location")).andReturn()); }
    String path(JsonNode draft) { return base() + "/" + draft.path("workflow").path("id").asString(); }
    String revision(long value) { return body(Map.of("expectedRevision", value)); }
    String save(long revision, String graph) { return body(Map.of("expectedRevision", revision, "name", "Edited", "description", "Updated", "definition", json.readTree(graph))); }


    String publish() throws Exception {
        String path=path(create());send(owner,post(path+"/publish").content(revision(0))).andExpect(status().isCreated());return path+"/executions";
    }
    String executionBase(){return "/api/v1/organizations/"+organization+"/executions";}
    ResultActions start(String path,String key,String input)throws Exception{return send(owner,post(path).header("Idempotency-Key",key).content(input));}
    void drain(){var worker=new com.bridgeops.executions.application.ExecutionWorker(runtime,capability,4,2,30,1000);for(int i=0;i<20;i++){var c=runtime.claim(UUID.randomUUID(),4,2,30);if(c==null)return;worker.dispatch(c);}throw new AssertionError("Worker did not quiesce");}
    @Test void acceptsPersistsReturnsLocationAndTerminalReplayIs200()throws Exception{
        String path=publish();var first=start(path,"http-start-key","{\"input\":{}}").andExpect(status().isAccepted()).andExpect(header().exists("Location")).andExpect(jsonPath("$.status").value("QUEUED")).andReturn();
        String exact=first.getResponse().getHeader("Location");String id=read(first).path("id").asString();
        start(path,"http-start-key","{\"input\":{}}").andExpect(status().isAccepted()).andExpect(jsonPath("$.id").value(id));
        drain();send(owner,get(exact)).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SUCCEEDED")).andExpect(jsonPath("$.retryEligible").value(false));
        start(path,"http-start-key","{\"input\":{}}").andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id));
        send(owner,get(exact+"/steps")).andExpect(status().isOk()).andExpect(jsonPath("$[1].nodeId").value("first")).andExpect(jsonPath("$[1].output.recorded").value(true));
        send(owner,get(exact+"/attempts")).andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(4));send(owner,get(exact+"/events")).andExpect(status().isOk()).andExpect(jsonPath("$.items[0].code").value("EXECUTION_QUEUED"));
        send(owner,get(executionBase()+"?status=SUCCEEDED&size=1")).andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1));
    }
    @Test void missingKeyStrictScalarsUnknownFieldsAndInputAreRejected()throws Exception{
        String path=publish();send(owner,post(path).content("{\"input\":{}}")).andExpect(status().isBadRequest());
        for(String body:List.of("{\"input\":{},\"delaySeconds\":1.5}","{\"input\":{},\"delaySeconds\":\"1\"}","{\"input\":{},\"delaySeconds\":null}","{\"input\":{},\"delaySeconds\":3601}","{\"input\":{},\"unknown\":true}","{\"input\":{},\"input\":{}}","{\"input\":{},\"versionId\":2}"))start(path,"invalid-type-key",body).andExpect(status().isBadRequest());
        start(path,"invalid-input-key","{\"input\":{\"secret\":\"synthetic-canary\"}}").andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("UNSUPPORTED_TRIGGER_INPUT"));
        start(path,"oversize-key"," ".repeat(262145)).andExpect(status().isContentTooLarge());
        assertThat(operator.queryForObject("SELECT count(*) FROM workflow_executions",Long.class)).isZero();
    }
    @Test void originCsrfAndLiveBearerRemainRequired()throws Exception{
        String path=publish();mvc.perform(post(path).contentType("application/json").content("{\"input\":{}}").header("Authorization","Bearer "+owner.access()).header("Origin",ORIGIN)).andExpect(status().isForbidden());
        mvc.perform(post(path).contentType("application/json").content("{\"input\":{}}").header("Authorization","Bearer "+owner.access()).header("Origin","https://hostile.invalid").cookie(owner.refresh(),owner.csrf()).header("X-CSRF-TOKEN",owner.csrf().getValue())).andExpect(status().isForbidden());
        mvc.perform(get(executionBase())).andExpect(status().isUnauthorized());sessions.revokeAll(owner.user());send(owner,get(executionBase())).andExpect(status().isUnauthorized());
    }
    @Test void foreignAndReadOnlyAccessIsDeniedOnAllExecutionFamilies()throws Exception{
        String path=publish();var e=read(start(path,"tenant-start-key","{\"input\":{}}").andExpect(status().isAccepted()).andReturn());String exact=executionBase()+"/"+e.path("id").asString();var viewer=viewer();
        send(viewer,get(exact)).andExpect(status().isOk());send(viewer,post(path).header("Idempotency-Key","viewer-start-key").content("{\"input\":{}}")).andExpect(status().isForbidden());send(viewer,post(exact+"/cancel").content("{\"expectedRevision\":0,\"reason\":\"Denied\"}")).andExpect(status().isForbidden());send(viewer,post(exact+"/retry").header("Idempotency-Key","viewer-retry-key").content("{\"expectedRevision\":0,\"reason\":\"Denied\"}")).andExpect(status().isForbidden());
        UUID foreign=organizations.create(owner.user(),owner.family(),"Foreign","http-execution-foreign").id();String other=exact.replace(organization.toString(),foreign.toString());
        for(String suffix:List.of("","/steps","/attempts","/events"))send(owner,get(other+suffix)).andExpect(status().isNotFound());
        for(String action:List.of("cancel","retry"))send(owner,post(other+"/"+action).header("Idempotency-Key","foreign-action-key").content("{\"expectedRevision\":0,\"reason\":\"Foreign\"}")).andExpect(status().isNotFound());
    }
    @Test void cancelRevisionAndSafeMutationTypesAreEnforced()throws Exception{
        String path=publish();var e=read(start(path,"cancel-http-key","{\"input\":{}}").andExpect(status().isAccepted()).andReturn());String exact=executionBase()+"/"+e.path("id").asString();
        send(owner,post(exact+"/cancel").content("{\"expectedRevision\":3,\"reason\":\"Stale\"}")).andExpect(status().isConflict());
        send(owner,post(exact+"/cancel").content("{\"expectedRevision\":0.5,\"reason\":\"Invalid\"}")).andExpect(status().isBadRequest());
        send(owner,post(exact+"/cancel").content("{\"expectedRevision\":0,\"reason\":2}")).andExpect(status().isBadRequest());
        send(owner,post(exact+"/cancel").content("{\"expectedRevision\":0,\"reason\":\"Stop requested\"}")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));drain();
        start(path,"cancel-http-key","{\"input\":{}}").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));send(owner,post(exact+"/retry").header("Idempotency-Key","cancel-retry-key").content("{\"expectedRevision\":1,\"reason\":\"Ineligible\"}")).andExpect(status().isConflict());
    }
    @Test void auditFailureReturnsSafe503AndRollsBackSubmission()throws Exception{
        String path=publish();operator.execute("CREATE FUNCTION fail_http_execution_audit() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.resource_type='EXECUTION' THEN RAISE EXCEPTION 'synthetic private database detail'; END IF; RETURN NEW; END $$");operator.execute("CREATE TRIGGER fail_http_execution_audit BEFORE INSERT ON organization_audit_events FOR EACH ROW EXECUTE FUNCTION fail_http_execution_audit()");
        try{String body=start(path,"audit-http-key","{\"input\":{}}").andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("DEPENDENCY_UNAVAILABLE")).andReturn().getResponse().getContentAsString();assertThat(body).doesNotContain("synthetic private","Exception","postgres");assertThat(operator.queryForObject("SELECT count(*) FROM workflow_executions",Long.class)).isZero();}
        finally{operator.execute("DROP TRIGGER fail_http_execution_audit ON organization_audit_events");operator.execute("DROP FUNCTION fail_http_execution_audit()");}
    }
    @AfterAll static void cleanup()throws Exception{keys.close();}
}
