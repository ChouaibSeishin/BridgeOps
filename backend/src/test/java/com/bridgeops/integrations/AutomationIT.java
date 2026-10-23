package com.bridgeops.integrations;

import com.bridgeops.identity.application.*;
import com.bridgeops.identity.domain.SessionOutcome.Granted;
import com.bridgeops.integrations.application.*;
import com.bridgeops.organizations.application.*;
import com.bridgeops.shared.web.DomainFailure;
import com.bridgeops.support.TestAuthMaterial;
import com.bridgeops.workflows.application.WorkflowService;
import com.bridgeops.workflows.domain.GraphDefinition;
import com.bridgeops.workflows.GraphV2DefinitionTest;
import jakarta.servlet.http.Cookie;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"bridgeops.auth.enabled=true","bridgeops.auth.origin=https://bridgeops.invalid","bridgeops.auth.issuer=automation-test","bridgeops.auth.key-id=automation-test","bridgeops.executions.worker.enabled=false"})
@AutoConfigureMockMvc
@Testcontainers
class AutomationIT {
    static final TestAuthMaterial keys = new TestAuthMaterial();
    static final java.nio.file.Path secretDirectory=secretDirectory();
    static java.nio.file.Path secretDirectory(){try{var dir=java.nio.file.Files.createTempDirectory("bridgeops-webhook-configuration-");byte[] key=new byte[32];new java.security.SecureRandom().nextBytes(key);var file=dir.resolve("local-v1.key");java.nio.file.Files.write(file,key);Arrays.fill(key,(byte)0);if(java.nio.file.Files.getFileStore(file).supportsFileAttributeView("posix"))java.nio.file.Files.setPosixFilePermissions(file,java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));return dir;}catch(Exception e){throw new ExceptionInInitializerError(e);}}
    @DynamicPropertySource static void secretProperties(DynamicPropertyRegistry r){r.add("bridgeops.integrations.secrets.directory",secretDirectory::toString);}
    @Container static final PostgreSQLContainer postgres = new PostgreSQLContainer(DockerImageName.parse("postgres@sha256:77f585114c32fbca283dc835b0596f4e52b51b4c6662d7810b2f4084f60a1873").asCompatibleSubstituteFor("postgres")).withInitScript("organization-runtime.sql");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) { r.add("spring.datasource.url", postgres::getJdbcUrl); r.add("spring.datasource.username", () -> "bridgeops_app"); r.add("spring.datasource.password", () -> "synthetic-runtime-fixture"); r.add("spring.flyway.user", postgres::getUsername); r.add("spring.flyway.password", postgres::getPassword); r.add("bridgeops.auth.private-key", () -> keys.directory.resolve("private.pem").toString()); r.add("bridgeops.auth.public-key", () -> keys.directory.resolve("public.pem").toString()); r.add("bridgeops.auth.csrf-key", () -> keys.directory.resolve("csrf.key").toString()); }
    @Autowired AutomationAuthority principals; @Autowired AutomationConfiguration configuration; @Autowired WorkflowService workflows; @Autowired ConnectionService connections; @Autowired GraphDefinition graphs;
    @Autowired OrganizationService organizations; @Autowired AccountPasswords passwords; @Autowired PasswordSessionService sessions; @Autowired AccessTokens tokens; @Autowired JdbcTemplate runtime; @Autowired PlatformTransactionManager manager; @Autowired MockMvc mvc;
    @Autowired WebhookConfiguration endpoints;
    @Autowired com.bridgeops.executions.persistence.ExecutionStore executions;
    @Autowired com.bridgeops.approvals.persistence.ApprovalStore approvals;
    @Autowired com.bridgeops.audit.application.ExecutionAudit executionAudit;
    @Autowired com.bridgeops.audit.application.ApprovalAudit approvalAudit;
    @Autowired com.bridgeops.audit.application.BusinessAudit businessAudit;
    @Autowired com.bridgeops.audit.application.IntegrationAudit integrationAudit;
    record Actor(UUID id, UUID family, String access, Cookie refresh, Cookie csrf) { @Override public String toString() { return "Actor[REDACTED]"; } }
    JdbcTemplate operator; Actor owner; UUID org, workflow, version; static String passwordHash;
    @BeforeEach void fixture() throws Exception {
        operator = new JdbcTemplate(new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())); operator.execute("TRUNCATE users,installation_state,authentication_throttles CASCADE"); operator.update("INSERT INTO installation_state(singleton_id) VALUES(1)");
        if (passwordHash == null) passwordHash = passwords.hash("automation-disposable-password"); owner = account("owner@example.invalid"); operator.update("INSERT INTO user_grants VALUES(?,'organization.create',?)", owner.id(), java.sql.Timestamp.from(Instant.now())); org = organizations.create(owner.id(), owner.family(), "Automation fixtures", "automation-fixtures").id(); publish(graphs.parse(GraphV2DefinitionTest.GRAPH.replace("manualTrigger", "webhookTrigger")));
    }
    Actor account(String email) throws Exception { UUID id = UUID.randomUUID(); var at = java.sql.Timestamp.from(Instant.now()); operator.update("INSERT INTO users(id,email,normalized_email,password_hash,display_name,account_status,created_at,email_verified_at) VALUES(?,?,?,?,?,'ACTIVE',?,?)", id, email, email, passwordHash, "Synthetic", at, at); var grant = (Granted) sessions.authenticate(email, "automation-disposable-password"); var refresh = new Cookie("__Host-bridgeops-refresh", grant.refreshToken()); var csrf = mvc.perform(get("/api/v1/auth/csrf").cookie(refresh)).andExpect(status().isOk()).andReturn().getResponse().getCookie("__Host-bridgeops-csrf"); return new Actor(id, grant.familyId(), tokens.issue(grant).value(), refresh, csrf); }
    Actor member(String role) throws Exception { var actor = account(role.toLowerCase(Locale.ROOT) + "@example.invalid"); UUID membership = UUID.randomUUID(); operator.update("INSERT INTO organization_memberships(id,organization_id,user_id,status,joined_at) VALUES(?,?,?,'ACTIVE',?)", membership, org, actor.id(), java.sql.Timestamp.from(Instant.now())); operator.update("INSERT INTO membership_roles SELECT ?,?,id FROM roles WHERE organization_id=? AND name=?", org, membership, org, role); return actor; }
    void publish(JsonNode graph) { workflow = workflows.create(owner.id(), owner.family(), org, "Pinned webhook definition", "", graph).workflow().id(); version = workflows.publish(owner.id(), owner.family(), org, workflow, 0).version().id(); }
    AutomationAuthority.Principal create() { return configuration.create(owner.id(), owner.family(), org, "Triage authority", workflow, version); }
    String base() { return "/api/v1/organizations/" + org + "/automations"; }
    String body() { return "{\"name\":\"Triage authority\",\"workflowId\":\"" + workflow + "\",\"versionId\":\"" + version + "\"}"; }
    org.springframework.test.web.servlet.ResultActions send(Actor actor, MockHttpServletRequestBuilder request) throws Exception { return mvc.perform(request.header("Authorization", "Bearer " + actor.access()).cookie(actor.refresh(), actor.csrf()).header("X-CSRF-Token", actor.csrf().getValue()).header("Origin", "https://bridgeops.invalid").contentType("application/json")); }
    void denied(int status, Runnable action) { assertThatThrownBy(action::run).isInstanceOfSatisfying(DomainFailure.class, e -> assertThat(e.status()).isEqualTo(status)); }
    long count(String table) { return operator.queryForObject("SELECT count(*) FROM " + table, Long.class); }
    void trusted(AutomationAuthority.Principal p, AutomationAuthority.Grant grant) { new TransactionTemplate(manager).executeWithoutResult(tx -> principals.operation(org, p.id(), p.workflowId(), p.versionId(), p.definitionHash(), grant)); }
    @AfterEach void dropAuditFixture() { operator.execute("DROP TRIGGER IF EXISTS test_automation_audit ON organization_audit_events");operator.execute("DROP TRIGGER IF EXISTS test_webhook_audit ON organization_audit_events"); }

    WebhookConfiguration.Revealed endpoint(){return endpoints.create(owner.id(),owner.family(),org,"Pinned delivery",create().id(),true);}
    com.bridgeops.executions.persistence.ExecutionStore.Execution automated(WebhookConfiguration.Endpoint endpoint){return new TransactionTemplate(manager).execute(tx->{
        var principal=principals.get(owner.id(),owner.family(),org,endpoint.principalId());UUID id=UUID.randomUUID();var context=new com.bridgeops.shared.domain.AutomationOrigin(principal.id(),principal.createdBy(),endpoint.id(),endpoint.revision(),endpoint.revisionAuthor());var now=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);executions.insertAutomation(id,org,endpoint.workflowId(),endpoint.versionId(),endpoint.definitionHash(),context,"{\"count\":1}","actor-fixture-"+id,"a".repeat(64),now);
        var definitions=new com.bridgeops.workflows.application.WorkflowDefinitions(graphs);var nodes=definitions.orderedV2(workflows.version(owner.id(),owner.family(),org,endpoint.workflowId(),endpoint.versionId()).definition());int position=0;for(var node:nodes)executions.insertStepV2(org,id,node.path("id").asString(),position++,node.path("type").asString(),UUID.randomUUID(),com.bridgeops.executions.domain.ExecutionPlan.hash(definitions.stableValue(node)));return executions.get(org,id,false);
    });}
    com.bridgeops.approvals.application.OperationApprovalIntent automatedIntent(com.bridgeops.executions.persistence.ExecutionStore.Execution e){return new TransactionTemplate(manager).execute(tx->{
        var step=executions.steps(org,e.id()).stream().filter(s->s.node().equals("positive")).findFirst().orElseThrow();var prepared=new com.bridgeops.executions.persistence.OperationInputStore(runtime).prepare(org,e.id(),step.node(),step.operation(),step.argumentHash(),"synthetic","record","WRITE",null,graphs.parse("{\"message\":\"Work exists\"}"),e.created());approvals.ensurePolicy(org,e.created());
        return com.bridgeops.approvals.application.OperationApprovalIntent.create(org,e.id(),step.node(),step.node(),e.version(),e.hash(),e.requester(),step.operation(),prepared.id(),"synthetic","record","WRITE",null,graphs.parse(prepared.arguments()),0,graphs.parse(approvals.policy(org).snapshot()),"Review exact automated record",e.created(),e.created().plusSeconds(3600),e.deadline());
    });}
    com.bridgeops.approvals.persistence.ApprovalStore.Request persist(com.bridgeops.approvals.application.OperationApprovalIntent intent){return new TransactionTemplate(manager).execute(tx->{var step=executions.steps(org,intent.execution()).stream().filter(s->s.node().equals(intent.actionNode())).findFirst().orElseThrow();return approvals.insert(intent,step.argumentHash(),executions.get(org,intent.execution(),false).automation());});}
    @Test void persistedAutomationUsesAnExplicitActorAndImmutableAcceptedEndpointRevision()throws Exception{
        var first=endpoint();var admin=member("ADMIN");var rotated=endpoints.rotate(admin.id(),admin.family(),org,first.endpoint().id(),0,true);var accepted=automated(rotated.endpoint());assertThat(accepted.actor()).isNull();assertThat(accepted.requester().type()).isEqualTo(com.bridgeops.shared.domain.ActorReference.Type.AUTOMATION);assertThat(accepted.requester().id()).isEqualTo(first.endpoint().principalId());assertThat(accepted.requester().configuringHumans()).containsExactlyElementsOf(java.util.stream.Stream.of(owner.id(),admin.id()).sorted(Comparator.comparing(UUID::toString)).toList());assertThat(accepted.source()).isEqualTo("WEBHOOK");assertThat(accepted.deadline()).isEqualTo(accepted.created().plusSeconds(86400));
        endpoints.rotate(owner.id(),owner.family(),org,first.endpoint().id(),1,true);assertThat(executions.get(org,accepted.id(),false).automation()).isEqualTo(accepted.automation());assertThat(count("users")).isEqualTo(2);assertThat(count("execution_work_items")).isEqualTo(1);
        var args=new com.bridgeops.shared.domain.AutomationOrigin(accepted.automation().principal(),accepted.automation().principalCreator(),accepted.automation().endpoint(),accepted.automation().endpointRevision(),owner.id());assertThatThrownBy(()->new TransactionTemplate(manager).executeWithoutResult(tx->executions.insertAutomation(UUID.randomUUID(),org,accepted.workflow(),accepted.version(),accepted.hash(),args,"{\"count\":1}","forged-author-fixture","b".repeat(64),Instant.now()))).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(()->operator.update("UPDATE workflow_executions SET endpoint_revision_author=?,revision=revision+1 WHERE id=?",owner.id(),accepted.id())).isInstanceOf(org.springframework.dao.DataAccessException.class);assertThat(count("workflow_executions")).isEqualTo(1);
    }
    @Test void persistedAutomationApprovalRejectsBothConfigurersAndSqlNullCannotMaskStaleDecisions()throws Exception{
        var first=endpoint();var admin=member("ADMIN");var rotated=endpoints.rotate(admin.id(),admin.family(),org,first.endpoint().id(),0,true);var e=automated(rotated.endpoint());var intent=automatedIntent(e);var request=persist(intent);assertThat(request.requester()).isNull();assertThat(request.actorReference()).isEqualTo(e.requester());assertThat(request.canonical()).isEqualTo(intent.canonical());
        for(var human:List.of(owner,admin))assertThatThrownBy(()->approvals.decide(request,human.id(),"APPROVE",null,e.created().plusSeconds(1))).isInstanceOf(org.springframework.dao.DataAccessException.class);
        var distinct=member("APPROVER");assertThatThrownBy(()->operator.update("INSERT INTO approval_decisions(id,organization_id,request_id,decided_by,decision,decided_at,expected_revision) VALUES(?,?,?,?,'APPROVE',?,1)",UUID.randomUUID(),org,request.id(),distinct.id(),java.sql.Timestamp.from(e.created().plusSeconds(1)))).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(()->approvals.decide(request,distinct.id(),"APPROVE",null,request.expires())).isInstanceOf(org.springframework.dao.DataAccessException.class);assertThat(count("approval_decisions")).isZero();approvals.decide(request,distinct.id(),"APPROVE",null,e.created().plusSeconds(1));assertThat(approvals.decision(org,request.id()).orElseThrow().actor()).isEqualTo(distinct.id());
    }
    @Test void forgedAutomationConfigurerExclusionsAndHumanSubstitutionCannotPersist()throws Exception{
        var e=automated(endpoint().endpoint());var actual=automatedIntent(e);var other=member("APPROVER");var forged=com.bridgeops.approvals.application.OperationApprovalIntent.create(org,e.id(),actual.gateNode(),actual.actionNode(),actual.version(),actual.definitionHash(),com.bridgeops.shared.domain.ActorReference.automation(e.requester().id(),other.id(),other.id()),actual.logicalOperation(),actual.preparedIntent(),actual.capability(),actual.operation(),actual.classification(),actual.connection(),graphs.parse(actual.arguments()),actual.policyVersion(),graphs.parse(actual.policySnapshot()),actual.prompt(),actual.createdAt(),actual.expiresAt(),actual.deadlineAt());assertThatThrownBy(()->persist(forged)).isInstanceOf(org.springframework.dao.DataAccessException.class);assertThat(count("approval_requests")).isZero();
        assertThatThrownBy(()->operator.update("UPDATE workflow_executions SET requested_by=?,requester_type='USER',automation_principal_id=NULL,principal_created_by=NULL,webhook_endpoint_id=NULL,webhook_endpoint_revision=NULL,endpoint_revision_author=NULL,trigger_source='MANUAL',revision=revision+1 WHERE id=?",owner.id(),e.id())).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(()->new TransactionTemplate(manager).executeWithoutResult(tx->executions.insertV2(UUID.randomUUID(),org,e.workflow(),e.version(),e.hash(),owner.id(),"WEBHOOK","{\"count\":1}","borrowed-human-fixture","b".repeat(64),null,e.created(),e.created()))).isInstanceOf(org.springframework.dao.DataAccessException.class);
    }
    @Test void typedAutomationAuditAndApprovalEventsNeverBorrowHumanActorIds(){
        var e=automated(endpoint().endpoint());var request=persist(automatedIntent(e));long before=count("organization_audit_events");new TransactionTemplate(manager).executeWithoutResult(tx->{executionAudit.write(org,e.requester(),com.bridgeops.audit.application.ExecutionAudit.Action.EXECUTION_STARTED,e.id(),0,e.version(),e.hash(),e.created());approvalAudit.write(org,e.requester(),com.bridgeops.audit.application.ApprovalAudit.Action.APPROVAL_REQUESTED,request.id(),0,e.created());businessAudit.write(org,e.requester(),e.id(),e.version(),e.hash(),com.bridgeops.audit.application.BusinessAudit.Action.INTERNAL_NOTIFICATION_RECORDED,UUID.randomUUID(),0,e.created());integrationAudit.write(org,e.requester(),com.bridgeops.audit.application.IntegrationAudit.Action.WEBHOOK_ACCEPTED,e.automation().endpoint(),e.automation().endpointRevision(),e.created());approvals.event(request,e.requester(),"APPROVAL_REQUESTED",e.created());});assertThat(count("organization_audit_events")).isEqualTo(before+4);assertThat(operator.queryForObject("SELECT count(*) FROM organization_audit_events WHERE actor_type='AUTOMATION' AND actor_id IS NULL AND automation_actor_id=?",Long.class,e.requester().id())).isEqualTo(4);assertThat(operator.queryForObject("SELECT actor_id IS NULL AND actor_type='AUTOMATION' AND automation_actor_id=? FROM approval_events WHERE request_id=?",Boolean.class,e.requester().id(),request.id())).isTrue();
    }
    @Test void automationQueueAndTypedAuditRequireTheSameAtomicDatabaseTransaction(){
        var e=endpoint().endpoint();var origin=new com.bridgeops.shared.domain.AutomationOrigin(e.principalId(),owner.id(),e.id(),0,owner.id());Instant now=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);UUID id=UUID.randomUUID();assertThatThrownBy(()->executions.insertAutomation(id,org,e.workflowId(),e.versionId(),e.definitionHash(),origin,"{\"count\":1}","no-transaction-fixture","a".repeat(64),now)).isInstanceOf(org.springframework.dao.InvalidDataAccessApiUsageException.class);assertThat(count("workflow_executions")).isZero();
        var unrelated=new org.springframework.jdbc.datasource.DataSourceTransactionManager(new DriverManagerDataSource(postgres.getJdbcUrl(),"bridgeops_app","synthetic-runtime-fixture"));assertThatThrownBy(()->new TransactionTemplate(unrelated).executeWithoutResult(tx->executions.insertAutomation(id,org,e.workflowId(),e.versionId(),e.definitionHash(),origin,"{\"count\":1}","unrelated-transaction-fixture","a".repeat(64),now))).isInstanceOf(org.springframework.dao.InvalidDataAccessApiUsageException.class);assertThat(count("execution_work_items")).isZero();
        failWebhookAudit();assertThatThrownBy(()->new TransactionTemplate(manager).executeWithoutResult(tx->{executions.insertAutomation(id,org,e.workflowId(),e.versionId(),e.definitionHash(),origin,"{\"count\":1}","audit-rollback-fixture","a".repeat(64),now);integrationAudit.write(org,origin.actor(),com.bridgeops.audit.application.IntegrationAudit.Action.WEBHOOK_ACCEPTED,e.id(),0,now);})).isInstanceOf(org.springframework.dao.DataAccessException.class);assertThat(count("workflow_executions")).isZero();assertThat(count("execution_work_items")).isZero();
    }
    @Test void endpointCreationPinsPrincipalAndEncryptsAOneTimeKeyWithoutStartingWork(){
        var value=endpoint();var e=value.endpoint();assertThat(e.versionId()).isEqualTo(version);assertThat(e.createdBy()).isEqualTo(owner.id());assertThat(e.revisionAuthor()).isEqualTo(owner.id());assertThat(Base64.getUrlDecoder().decode(value.signingSecret())).hasSize(32);assertThat(value.toString()).doesNotContain(value.signingSecret());assertThat(endpoints.get(owner.id(),owner.family(),org,e.id())).isEqualTo(e);
        assertThat(count("webhook_endpoints")).isEqualTo(1);assertThat(count("webhook_endpoint_versions")).isEqualTo(1);assertThat(count("integration_secrets")).isEqualTo(1);assertThat(count("users")).isEqualTo(1);assertThat(count("workflow_executions")).isZero();assertThat(count("execution_work_items")).isZero();assertThat(count("approval_requests")).isZero();
        String metadata=tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(endpoints.list(owner.id(),owner.family(),org,0,25));assertThat(metadata).doesNotContain(value.signingSecret(),"signingSecret","ciphertext","signing_secret_id");assertThat(operator.queryForObject("SELECT bool_and(purpose='WEBHOOK_SIGNING' AND octet_length(ciphertext)=48) FROM integration_secrets",Boolean.class)).isTrue();
        assertThat(operator.queryForObject("SELECT string_agg(row_to_json(a)::text,'') FROM organization_audit_events a",String.class)).doesNotContain(value.signingSecret());
    }
    @Test void endpointRotationPreservesAcceptedRevisionAuthorsAndScopeWhileRevocationIsTerminal()throws Exception{
        var first=endpoint();var admin=member("ADMIN");var rotated=endpoints.rotate(admin.id(),admin.family(),org,first.endpoint().id(),0,true);assertThat(rotated.signingSecret()).isNotEqualTo(first.signingSecret());assertThat(rotated.endpoint().revision()).isEqualTo(1);assertThat(rotated.endpoint().createdBy()).isEqualTo(owner.id());assertThat(rotated.endpoint().revisionAuthor()).isEqualTo(admin.id());assertThat(rotated.endpoint().principalId()).isEqualTo(first.endpoint().principalId());assertThat(rotated.endpoint().definitionHash()).isEqualTo(first.endpoint().definitionHash());
        assertThat(operator.queryForList("SELECT authored_by FROM webhook_endpoint_versions WHERE endpoint_id=? ORDER BY revision",UUID.class,first.endpoint().id())).containsExactly(owner.id(),admin.id());denied(409,()->endpoints.rotate(owner.id(),owner.family(),org,first.endpoint().id(),0,true));
        var revoked=endpoints.revoke(owner.id(),owner.family(),org,first.endpoint().id(),1);assertThat(revoked.status()).isEqualTo("REVOKED");assertThat(revoked.revision()).isEqualTo(2);denied(409,()->endpoints.rotate(owner.id(),owner.family(),org,first.endpoint().id(),2,true));denied(409,()->endpoints.revoke(owner.id(),owner.family(),org,first.endpoint().id(),2));assertThat(count("integration_secrets")).isEqualTo(2);
        assertThatThrownBy(()->operator.update("UPDATE webhook_endpoints SET status='ACTIVE',revision=revision+1 WHERE id=?",first.endpoint().id())).isInstanceOf(org.springframework.dao.DataAccessException.class);
    }
    @Test void endpointConfigurationRequiresTheRoleIntersectionAndLiveScopedAuthority()throws Exception{
        var p=create();for(String role:List.of("BUILDER","OPERATOR","VIEWER","APPROVER")){var a=member(role);denied(403,()->endpoints.create(a.id(),a.family(),org,"Denied",p.id(),true));}denied(422,()->endpoints.create(owner.id(),owner.family(),org,"Unconfirmed",p.id(),false));assertThat(count("webhook_endpoints")).isZero();
        var first=endpoints.create(owner.id(),owner.family(),org,"Pinned",p.id(),true);principals.revoke(owner.id(),owner.family(),org,p.id(),0);denied(403,()->endpoints.create(owner.id(),owner.family(),org,"Revoked",p.id(),true));denied(403,()->endpoints.rotate(owner.id(),owner.family(),org,first.endpoint().id(),0,true));assertThat(endpoints.revoke(owner.id(),owner.family(),org,first.endpoint().id(),0).status()).isEqualTo("REVOKED");
    }
    @Test void endpointForeignReadsAndPrincipalReferencesAreConcealed()throws Exception{
        var first=endpoint();var outsider=account("endpoint-outsider@example.invalid");denied(404,()->endpoints.get(outsider.id(),outsider.family(),org,first.endpoint().id()));UUID other=organizations.create(owner.id(),owner.family(),"Other endpoint tenant","other-endpoint").id();denied(404,()->endpoints.get(owner.id(),owner.family(),other,first.endpoint().id()));denied(404,()->endpoints.create(owner.id(),owner.family(),other,"Foreign principal",first.endpoint().principalId(),true));
        var viewer=member("VIEWER");assertThat(endpoints.list(viewer.id(),viewer.family(),org,0,1).items()).containsExactly(first.endpoint());operator.update("UPDATE organization_memberships SET status='REMOVED',revision=revision+1 WHERE organization_id=? AND user_id=?",org,viewer.id());denied(404,()->endpoints.get(viewer.id(),viewer.family(),org,first.endpoint().id()));
        assertThatThrownBy(()->operator.update("INSERT INTO webhook_endpoints SELECT ?,?,name,principal_id,principal_created_by,workflow_id,workflow_version_id,definition_hash,graph_schema_version,revision,status,signing_secret_id,created_by,created_at,updated_by,updated_at FROM webhook_endpoints WHERE id=?",UUID.randomUUID(),other,first.endpoint().id())).isInstanceOf(org.springframework.dao.DataAccessException.class);
    }
    @Test void endpointVersionHistoryAndPinnedColumnsHaveNoRuntimeMutationPrivileges(){
        var first=endpoint();for(String privilege:List.of("UPDATE","DELETE","TRUNCATE"))assertThat(operator.queryForObject("SELECT has_table_privilege('bridgeops_app','webhook_endpoint_versions',?)",Boolean.class,privilege)).isFalse();for(String column:List.of("principal_id","principal_created_by","workflow_version_id","definition_hash","created_by","created_at","name"))assertThat(operator.queryForObject("SELECT has_column_privilege('bridgeops_app','webhook_endpoints',?,'UPDATE')",Boolean.class,column)).isFalse();
        assertThatThrownBy(()->operator.update("UPDATE webhook_endpoint_versions SET authored_by=authored_by WHERE endpoint_id=?",first.endpoint().id())).isInstanceOf(org.springframework.dao.DataAccessException.class);assertThatThrownBy(()->operator.update("UPDATE webhook_endpoints SET workflow_version_id=?,revision=revision+1 WHERE id=?",UUID.randomUUID(),first.endpoint().id())).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(()->operator.update("DELETE FROM webhook_endpoint_versions WHERE endpoint_id=?",first.endpoint().id())).isInstanceOf(org.springframework.dao.DataAccessException.class);
    }
    void failWebhookAudit(){operator.execute("CREATE OR REPLACE FUNCTION test_webhook_audit_failure() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.action LIKE 'WEBHOOK_%' THEN RAISE EXCEPTION 'Controlled webhook audit failure'; END IF; RETURN NEW; END $$");operator.execute("CREATE TRIGGER test_webhook_audit BEFORE INSERT ON organization_audit_events FOR EACH ROW EXECUTE FUNCTION test_webhook_audit_failure()");}
    @Test void endpointAuditFailureRollsBackCreationRotationRevocationAndSecretEnvelopes(){
        var p=create();failWebhookAudit();assertThatThrownBy(()->endpoints.create(owner.id(),owner.family(),org,"Audit rollback",p.id(),true)).isInstanceOf(org.springframework.dao.DataAccessException.class);assertThat(count("webhook_endpoints")).isZero();assertThat(count("integration_secrets")).isZero();operator.execute("DROP TRIGGER test_webhook_audit ON organization_audit_events");var first=endpoints.create(owner.id(),owner.family(),org,"Pinned",p.id(),true);failWebhookAudit();
        assertThatThrownBy(()->endpoints.rotate(owner.id(),owner.family(),org,first.endpoint().id(),0,true)).isInstanceOf(org.springframework.dao.DataAccessException.class);assertThatThrownBy(()->endpoints.revoke(owner.id(),owner.family(),org,first.endpoint().id(),0)).isInstanceOf(org.springframework.dao.DataAccessException.class);assertThat(endpoints.get(owner.id(),owner.family(),org,first.endpoint().id())).isEqualTo(first.endpoint());assertThat(count("integration_secrets")).isEqualTo(1);assertThat(count("webhook_endpoint_versions")).isEqualTo(1);
    }
    @Test void concurrentEndpointRotationsHaveOneNewKeyAndOneConflict()throws Exception{
        var first=endpoint();java.util.function.Supplier<Integer> rotate=()->{try{endpoints.rotate(owner.id(),owner.family(),org,first.endpoint().id(),0,true);return 200;}catch(DomainFailure e){return e.status();}};
        try(var pool=Executors.newFixedThreadPool(2)){var a=pool.submit(rotate::get);var b=pool.submit(rotate::get);assertThat(List.of(a.get(15,TimeUnit.SECONDS),b.get(15,TimeUnit.SECONDS))).containsExactlyInAnyOrder(200,409);}assertThat(count("webhook_endpoint_versions")).isEqualTo(2);assertThat(count("integration_secrets")).isEqualTo(2);
    }
    @Test void databaseRejectsWrongSecretPurposeLengthAndUnmatchedCurrentVersion(){
        var first=endpoint();for(String purpose:List.of("REST_CREDENTIAL","WEBHOOK_SIGNING")){UUID ref=UUID.randomUUID();operator.update("INSERT INTO integration_secrets(id,organization_id,purpose,key_id,nonce,ciphertext,created_at) VALUES(?,?,?,'fixture',?,?,now())",ref,org,purpose,new byte[12],new byte[purpose.equals("WEBHOOK_SIGNING")?17:48]);assertThatThrownBy(()->operator.update("UPDATE webhook_endpoints SET signing_secret_id=?,revision=revision+1,updated_at=now() WHERE id=?",ref,first.endpoint().id())).isInstanceOf(org.springframework.dao.DataAccessException.class);}
        UUID validShape=UUID.randomUUID();operator.update("INSERT INTO integration_secrets(id,organization_id,purpose,key_id,nonce,ciphertext,created_at) VALUES(?,?,'WEBHOOK_SIGNING','fixture',?,?,now())",validShape,org,new byte[12],new byte[48]);assertThatThrownBy(()->operator.update("UPDATE webhook_endpoints SET signing_secret_id=?,revision=revision+1,updated_at=now() WHERE id=?",validShape,first.endpoint().id())).isInstanceOf(org.springframework.dao.DataAccessException.class);assertThat(endpoints.get(owner.id(),owner.family(),org,first.endpoint().id())).isEqualTo(first.endpoint());
    }
    @Test void endpointHttpLifecycleRetainsSecurityStrictTypesAndOneTimeReveal()throws Exception{
        String route="/api/v1/organizations/"+org+"/webhook-endpoints";String body="{\"name\":\"Pinned endpoint\",\"principalId\":\""+create().id()+"\",\"confirmed\":true}";
        mvc.perform(post(route).contentType("application/json").content(body)).andExpect(status().isForbidden());mvc.perform(post(route).header("Authorization","Bearer "+owner.access()).cookie(owner.refresh(),owner.csrf()).header("X-CSRF-Token",owner.csrf().getValue()).header("Origin","https://evil.invalid").contentType("application/json").content(body)).andExpect(status().isForbidden());
        mvc.perform(post(route).header("Authorization","Bearer "+owner.access()).header("Origin","https://bridgeops.invalid").contentType("application/json").content(body)).andExpect(status().isForbidden());
        send(owner,post(route).content(body.replace("true","\"true\""))).andExpect(status().isBadRequest());send(owner,post(route).content(body.replace("\"name\":","\"signingSecret\":\"injected\",\"name\":"))).andExpect(status().isBadRequest());send(owner,post(route).content(body.replace("true","false"))).andExpect(status().isBadRequest());send(owner,post(route).content(body+" ".repeat(256*1024))).andExpect(status().isPayloadTooLarge());
        var result=send(owner,post(route).content(body)).andExpect(status().isCreated()).andExpect(header().string("Cache-Control","no-store")).andReturn();var json=graphs.parse(result.getResponse().getContentAsString());UUID id=UUID.fromString(json.path("endpoint").path("id").asString());String key=json.path("signingSecret").asString();assertThat(key).hasSize(43);
        var read=send(owner,get(route+"/"+id)).andExpect(status().isOk()).andExpect(jsonPath("$.signingSecret").doesNotExist()).andReturn().getResponse().getContentAsString();assertThat(read).doesNotContain(key,"secretReference","signing_secret_id");var viewer=member("VIEWER");send(viewer,post(route+"/"+id+"/rotate").content("{\"expectedRevision\":0,\"confirmed\":true}")).andExpect(status().isForbidden());
        send(owner,post(route+"/"+id+"/rotate").content("{\"expectedRevision\":\"0\",\"confirmed\":true}")).andExpect(status().isBadRequest());var rotated=send(owner,post(route+"/"+id+"/rotate").content("{\"expectedRevision\":0,\"confirmed\":true}")).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andReturn();assertThat(rotated.getResponse().getContentAsString()).doesNotContain(key);send(owner,post(route+"/"+id+"/revoke").content("{\"expectedRevision\":1}")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REVOKED"));
        send(owner,get(route).param("size","101")).andExpect(status().isBadRequest());mvc.perform(post("/api/v1/webhooks/"+id).contentType("application/json").content("{}")).andExpect(status().isForbidden());assertThat(count("workflow_executions")).isZero();
    }

    @Test void derivesExactGrantsWithoutInventingUserOrStartingExecution() {
        var p = create(); assertThat(p.actorType()).isEqualTo("AUTOMATION"); assertThat(p.createdBy()).isEqualTo(owner.id()); assertThat(p.versionId()).isEqualTo(version);
        assertThat(p.grants()).containsExactly(new AutomationAuthority.Grant("empty", null, null, "synthetic.record", "WRITE"), new AutomationAuthority.Grant("positive", null, null, "synthetic.record", "WRITE"));
        assertThat(count("users")).isEqualTo(1); assertThat(count("workflow_executions")).isZero(); assertThat(count("execution_work_items")).isZero(); assertThat(count("approval_requests")).isZero(); assertThat(count("integration_secrets")).isZero();
        assertThat(operator.queryForObject("SELECT count(*) FROM organization_audit_events WHERE action='AUTOMATION_CREATED' AND actor_id=?", Long.class, owner.id())).isEqualTo(1);
    }
    @Test void buildersOperatorsViewersAndApproversCannotManufactureRunAuthority() throws Exception {
        for (String role : List.of("BUILDER", "OPERATOR", "VIEWER", "APPROVER")) { var actor = member(role); denied(403, () -> configuration.create(actor.id(), actor.family(), org, "Denied", workflow, version)); }
        assertThat(count("automation_principals")).isZero(); var admin = member("ADMIN"); assertThat(configuration.create(admin.id(), admin.family(), org, "Admin scoped", workflow, version).status()).isEqualTo("ACTIVE");
    }
    @Test void scopeChecksEveryNodeOperationClassificationAndPinnedVersion() {
        var p = create(); trusted(p, p.grants().getFirst());
        for (var denied : List.of(new AutomationAuthority.Grant("missing", null, null, "synthetic.record", "WRITE"), new AutomationAuthority.Grant("empty", null, null, "synthetic.record", "READ"), new AutomationAuthority.Grant("empty", null, null, "synthetic.other", "WRITE"))) denied(403, () -> trusted(p, denied));
        denied(403, () -> new TransactionTemplate(manager).executeWithoutResult(tx -> principals.worker(org, p.id(), workflow, UUID.randomUUID(), p.definitionHash())));
        denied(403, () -> new TransactionTemplate(manager).executeWithoutResult(tx -> principals.worker(org, p.id(), workflow, version, "a".repeat(64))));
    }
    @Test void revocationIsTerminalAndPreventsFurtherTrustedAuthority() {
        var p = create(); var revoked = principals.revoke(owner.id(), owner.family(), org, p.id(), 0); assertThat(revoked.status()).isEqualTo("REVOKED"); assertThat(revoked.revision()).isEqualTo(1); assertThat(revoked.grants()).isEqualTo(p.grants());
        denied(403, () -> trusted(p, p.grants().getFirst())); denied(409, () -> principals.revoke(owner.id(), owner.family(), org, p.id(), 0));
        assertThatThrownBy(() -> operator.update("UPDATE automation_principals SET status='ACTIVE',revision=0,revoked_at=NULL,revoked_by=NULL WHERE id=?", p.id())).isInstanceOf(org.springframework.dao.DataAccessException.class);
    }
    @Test void creatorSessionIsNotBorrowedAsWorkerIdentity() { var p = create(); sessions.revokeFamily(owner.id(), owner.family()); trusted(p, p.grants().getFirst()); denied(401, () -> configuration.create(owner.id(), owner.family(), org, "Expired session", workflow, version)); }
    @Test void readersHaveLiveMembershipAndForeignScopesAreConcealed() throws Exception {
        var p = create(); var viewer = member("VIEWER"); assertThat(principals.list(viewer.id(), viewer.family(), org, 0, 1).items()).containsExactly(p);
        UUID foreign = organizations.create(owner.id(), owner.family(), "Other tenant", "other-automation").id(); denied(404, () -> principals.get(owner.id(), owner.family(), foreign, p.id()));
        var outsider = account("outsider@example.invalid"); denied(404, () -> principals.get(outsider.id(), outsider.family(), org, p.id()));
        operator.update("UPDATE organization_memberships SET status='REMOVED',revision=revision+1 WHERE organization_id=? AND user_id=?", org, viewer.id()); denied(404, () -> principals.get(viewer.id(), viewer.family(), org, p.id()));
    }
    @Test void internalConnectionGrantPinsRevisionAndCannotExpandToChangedOrForeignConnection() {
        var connection = connections.create(owner.id(), owner.family(), org, "Support", "", "SUPPORT", graphs.parse("{}"), null);
        var graph = (ObjectNode) graphs.parse(GraphV2DefinitionTest.GRAPH.replace("manualTrigger", "webhookTrigger"));
        for (var n : graph.path("nodes")) if (n.path("id").asString().equals("copy")) { var node = (ObjectNode) n; node.put("type", "capabilityAction"); node.set("config", graphs.parse("{\"integrationId\":\"" + connection.connection().id() + "\",\"revision\":0,\"operation\":\"support.list\"}")); node.set("inputs", graphs.parse("{\"limit\":{\"literal\":50},\"priorities\":{\"literal\":[\"HIGH\",\"URGENT\"]},\"statuses\":{\"literal\":[\"OPEN\",\"IN_PROGRESS\"]}}")); }
        publish(graph); var p = create(); assertThat(p.grants()).contains(new AutomationAuthority.Grant("copy", connection.connection().id(), 0L, "support.list", "READ"));
        trusted(p, new AutomationAuthority.Grant("copy", connection.connection().id(), 0L, "support.list", "READ")); denied(403, () -> trusted(p, new AutomationAuthority.Grant("copy", connection.connection().id(), 1L, "support.list", "READ")));
        connections.update(owner.id(), owner.family(), org, connection.connection().id(), 0, "Support", "", false, graphs.parse("{}"), null, false); denied(409, this::create);
    }
    @Test void manualOrArchivedVersionsCannotAcquireWebhookAuthority() {
        publish(graphs.parse(GraphV2DefinitionTest.GRAPH)); denied(422, this::create);
        publish(graphs.parse(GraphV2DefinitionTest.GRAPH.replace("manualTrigger", "webhookTrigger"))); workflows.archive(owner.id(), owner.family(), org, workflow, 1); denied(409, this::create); assertThat(count("automation_principals")).isZero();
    }
    @Test void immutableGrantAndIdentityColumnsRetainDatabasePrivilegeBoundary() {
        var p = create(); for (String table : List.of("automation_operation_grants")) for (String privilege : List.of("UPDATE", "DELETE", "TRUNCATE")) assertThat(operator.queryForObject("SELECT has_table_privilege('bridgeops_app',?,?)", Boolean.class, table, privilege)).isFalse();
        assertThatThrownBy(() -> runtime.update("UPDATE automation_principals SET created_by=? WHERE id=?", UUID.randomUUID(), p.id())).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(() -> operator.update("UPDATE automation_operation_grants SET operation='other' WHERE principal_id=?", p.id())).isInstanceOf(org.springframework.dao.DataAccessException.class);
    }
    @Test void auditFailureRollsBackPrincipalAndGrantCreationAndRevocation() {
        operator.execute("CREATE OR REPLACE FUNCTION test_automation_audit_failure() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.action IN ('AUTOMATION_CREATED','AUTOMATION_REVOKED') THEN RAISE EXCEPTION 'Controlled audit failure'; END IF; RETURN NEW; END $$"); operator.execute("CREATE TRIGGER test_automation_audit BEFORE INSERT ON organization_audit_events FOR EACH ROW EXECUTE FUNCTION test_automation_audit_failure()");
        assertThatThrownBy(this::create).isInstanceOf(org.springframework.dao.DataAccessException.class); assertThat(count("automation_principals")).isZero(); assertThat(count("automation_operation_grants")).isZero();
        operator.execute("DROP TRIGGER test_automation_audit ON organization_audit_events"); var p = create(); operator.execute("CREATE TRIGGER test_automation_audit BEFORE INSERT ON organization_audit_events FOR EACH ROW EXECUTE FUNCTION test_automation_audit_failure()");
        assertThatThrownBy(() -> principals.revoke(owner.id(), owner.family(), org, p.id(), 0)).isInstanceOf(org.springframework.dao.DataAccessException.class); assertThat(principals.get(owner.id(), owner.family(), org, p.id()).status()).isEqualTo("ACTIVE");
    }
    @Test void concurrentRevocationsHaveOneCommittedLifecycleResult() throws Exception {
        var p = create(); java.util.function.Supplier<Integer> revoke = () -> { try { principals.revoke(owner.id(), owner.family(), org, p.id(), 0); return 200; } catch (DomainFailure conflict) { return conflict.status(); } };
        try (var pool = Executors.newFixedThreadPool(2)) { var a = pool.submit(revoke::get); var b = pool.submit(revoke::get); assertThat(List.of(a.get(15, TimeUnit.SECONDS), b.get(15, TimeUnit.SECONDS))).containsExactlyInAnyOrder(200, 409); }
        assertThat(operator.queryForObject("SELECT count(*) FROM organization_audit_events WHERE action='AUTOMATION_REVOKED'", Long.class)).isEqualTo(1);
    }
    @Test void authenticatedHttpLifecycleKeepsExactOriginCsrfStrictBodiesAndRoleChecks() throws Exception {
        mvc.perform(post(base()).contentType("application/json").content(body())).andExpect(status().isForbidden());
        mvc.perform(post(base()).header("Authorization", "Bearer " + owner.access()).cookie(owner.refresh(), owner.csrf()).header("X-CSRF-Token", owner.csrf().getValue()).header("Origin", "https://evil.invalid").contentType("application/json").content(body())).andExpect(status().isForbidden());
        send(owner, post(base()).content(body().replace("\"name\":", "\"grants\":[],\"name\":"))).andExpect(status().isBadRequest());
        var created = send(owner, post(base()).content(body())).andExpect(status().isCreated()).andExpect(jsonPath("$.actorType").value("AUTOMATION")).andReturn(); UUID id = UUID.fromString(graphs.parse(created.getResponse().getContentAsString()).path("id").asString());
        send(owner, get(base() + "/" + id)).andExpect(status().isOk()).andExpect(jsonPath("$.grants.length()").value(2));
        var builder = member("BUILDER"); send(builder, post(base() + "/" + id + "/revoke").content("{\"expectedRevision\":0}")).andExpect(status().isForbidden());
        send(owner, post(base() + "/" + id + "/revoke").content("{\"expectedRevision\":\"0\"}")).andExpect(status().isBadRequest());
        send(owner, post(base() + "/" + id + "/revoke").content("{\"expectedRevision\":0}")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REVOKED"));
        send(owner, get(base()).param("size", "101")).andExpect(status().isBadRequest());
    }
}
