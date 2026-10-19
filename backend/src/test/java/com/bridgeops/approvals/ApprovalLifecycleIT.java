package com.bridgeops.approvals;

import com.bridgeops.approvals.application.*;
import com.bridgeops.approvals.domain.ApprovalState;
import com.bridgeops.executions.application.*;
import com.bridgeops.executions.domain.*;
import com.bridgeops.executions.persistence.ExecutionStore;
import com.bridgeops.identity.application.*;
import com.bridgeops.identity.domain.SessionOutcome.Granted;
import com.bridgeops.organizations.application.OrganizationService;
import com.bridgeops.shared.web.DomainFailure;
import com.bridgeops.support.TestAuthMaterial;
import com.bridgeops.workflows.application.WorkflowService;
import com.bridgeops.workflows.domain.GraphDefinition;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties={"bridgeops.auth.enabled=true","bridgeops.auth.origin=https://bridgeops.invalid","bridgeops.auth.issuer=approval-test","bridgeops.auth.key-id=approval-test","bridgeops.executions.worker.enabled=false"})
@Testcontainers
class ApprovalLifecycleIT {
    static final TestAuthMaterial keys=new TestAuthMaterial();
    @Container static final PostgreSQLContainer postgres=new PostgreSQLContainer(DockerImageName.parse("postgres@sha256:77f585114c32fbca283dc835b0596f4e52b51b4c6662d7810b2f4084f60a1873").asCompatibleSubstituteFor("postgres")).withInitScript("organization-runtime.sql");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r){r.add("spring.datasource.url",postgres::getJdbcUrl);r.add("spring.datasource.username",()->"bridgeops_app");r.add("spring.datasource.password",()->"synthetic-runtime-fixture");r.add("spring.flyway.user",postgres::getUsername);r.add("spring.flyway.password",postgres::getPassword);r.add("bridgeops.auth.private-key",()->keys.directory.resolve("private.pem").toString());r.add("bridgeops.auth.public-key",()->keys.directory.resolve("public.pem").toString());r.add("bridgeops.auth.csrf-key",()->keys.directory.resolve("csrf.key").toString());}
    static class MutableClock extends Clock {volatile Instant now=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId zone){return this;}public Instant instant(){return now;}void add(long seconds){now=now.plusSeconds(seconds);}}
    static class ControlledCapability extends SyntheticCapability {volatile boolean interrupt;@Override public void afterCommit(Invocation invocation){if(interrupt){interrupt=false;throw new IllegalStateException("Controlled interruption");}}}
    @TestConfiguration static class Configuration {@Bean @Primary MutableClock controlledClock(){return new MutableClock();}@Bean @Primary ControlledCapability controlledCapability(){return new ControlledCapability();}}
    @Autowired ExecutionService executions;@Autowired ExecutionRuntime runtime;@Autowired ExecutionStore executionStore;
    @Autowired ApprovalService approvals;@Autowired ApprovalRuntime approvalRuntime;@Autowired WorkflowService workflows;@Autowired GraphDefinition graphs;
    @Autowired AccountPasswords passwords;@Autowired PasswordSessionService sessions;@Autowired OrganizationService organizations;
    @Autowired MutableClock clock;@Autowired ControlledCapability capability;
    JdbcTemplate operator;Actor owner,approver;UUID org,workflow,version;ExecutionWorker worker;static String passwordHash;
    record Actor(UUID id,UUID family) {}
    static final String GRAPH="""
      {"schemaVersion":1,"nodes":[
      {"id":"finish","type":"end","typeVersion":1,"config":{}},
      {"id":"protected","type":"syntheticAction","typeVersion":1,"config":{"operation":"record","message":"Exact protected record"}},
      {"id":"start","type":"manualTrigger","typeVersion":1,"config":{}},
      {"id":"gate","type":"approval","typeVersion":1,"config":{"prompt":"Review exact internal record"}},
      {"id":"prepare","type":"syntheticAction","typeVersion":1,"config":{"operation":"record","message":"Preparation record"}}],
      "edges":[{"source":"start","target":"prepare"},{"source":"prepare","target":"gate"},{"source":"gate","target":"protected"},{"source":"protected","target":"finish"}]}
      """;
    @BeforeEach void fixture(){
        operator=new JdbcTemplate(new DriverManagerDataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword()));
        operator.execute("TRUNCATE users,installation_state,authentication_throttles CASCADE");operator.update("INSERT INTO installation_state(singleton_id) VALUES(1)");clock.now=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);capability.interrupt=false;
        if(passwordHash==null)passwordHash=passwords.hash("approval-disposable-password");owner=account("owner@example.invalid");operator.update("INSERT INTO user_grants VALUES(?,'organization.create',?)",owner.id(),java.sql.Timestamp.from(clock.instant()));
        org=organizations.create(owner.id(),owner.family(),"Approval organization","approval-test").id();approver=member("APPROVER");publish(GRAPH);
        worker=new ExecutionWorker(runtime,capability,4,2,30,1000);
    }
    Actor account(String email){UUID id=UUID.randomUUID();var at=java.sql.Timestamp.from(clock.instant());operator.update("INSERT INTO users(id,email,normalized_email,password_hash,display_name,account_status,created_at,email_verified_at) VALUES(?,?,?,?,?,'ACTIVE',?,?)",id,email,email,passwordHash,"Synthetic",at,at);var grant=(Granted)sessions.authenticate(email,"approval-disposable-password");return new Actor(id,grant.familyId());}
    Actor member(String role){var actor=account(role.toLowerCase(Locale.ROOT)+"@example.invalid");UUID membership=UUID.randomUUID();operator.update("INSERT INTO organization_memberships(id,organization_id,user_id,status,joined_at) VALUES(?,?,?,'ACTIVE',?)",membership,org,actor.id(),java.sql.Timestamp.from(clock.instant()));operator.update("INSERT INTO membership_roles SELECT ?,?,id FROM roles WHERE organization_id=? AND name=?",org,membership,org,role);return actor;}
    void publish(String graph){workflow=workflows.create(owner.id(),owner.family(),org,"Internal governance","",graphs.parse(graph)).workflow().id();version=workflows.publish(owner.id(),owner.family(),org,workflow,0).version().id();}
    ExecutionViews.Summary start(){return executions.start(owner.id(),owner.family(),org,workflow,version,graphs.parse("{}"),0,UUID.randomUUID().toString()).execution();}
    ExecutionViews.Summary get(UUID execution){return executions.get(owner.id(),owner.family(),org,execution);}
    void drain(){for(int i=0;i<100;i++){runtime.reconcile();var claim=runtime.claim(UUID.randomUUID(),4,2,30);if(claim==null)return;worker.dispatch(claim);}throw new AssertionError("Worker did not quiesce");}
    ApprovalService.View request(UUID execution){var linked=get(execution).approvalId();return linked==null?approvals.list(owner.id(),owner.family(),org,0,100,null,execution).items().getFirst():approvals.get(owner.id(),owner.family(),org,linked);}
    ApprovalService.View decide(Actor actor,ApprovalService.View request,String decision){return approvals.decide(actor.id(),actor.family(),org,request.id(),request.revision(),decision,"Reviewed exact record");}
    long count(String table){return operator.queryForObject("SELECT count(*) FROM "+table,Long.class);}
    static void denied(int status,Runnable action){assertThatThrownBy(action::run).isInstanceOfSatisfying(DomainFailure.class,e->assertThat(e.status()).isEqualTo(status));}
    void auditFailure(String action){operator.execute("CREATE OR REPLACE FUNCTION test_approval_audit_failure() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.action='"+action+"' THEN RAISE EXCEPTION 'Controlled audit failure'; END IF; RETURN NEW; END $$");operator.execute("CREATE TRIGGER controlled_approval_audit BEFORE INSERT ON organization_audit_events FOR EACH ROW EXECUTE FUNCTION test_approval_audit_failure()");}
    @AfterEach void removeFailure(){operator.execute("DROP TRIGGER IF EXISTS controlled_approval_audit ON organization_audit_events");}
    @Test void distinctHumanApprovalResumesSamePinnedExecutionAndRecordsProtectedEffectExactlyOnce(){
        var execution=start();drain();assertThat(get(execution.id()).status()).isEqualTo(ExecutionState.WAITING_FOR_APPROVAL);assertThat(count("synthetic_records")).isEqualTo(1);
        var r=request(execution.id());assertThat(r.arguments().path("message").asString()).isEqualTo("Exact protected record");assertThat(r.versionId()).isEqualTo(version);assertThat(r.policyVersion()).isZero();assertThat(r.deadlineAt()).isEqualTo(execution.deadlineAt());
        assertThat(operator.queryForObject("SELECT state FROM execution_work_items",String.class)).isEqualTo("WAITING");assertThat(runtime.claim(UUID.randomUUID(),4,2,30)).isNull();
        denied(403,()->decide(owner,r,"APPROVE"));var approved=decide(approver,r,"APPROVE");assertThat(approved.status()).isEqualTo(ApprovalState.APPROVED);assertThat(approved.executionStatus()).isEqualTo("WAITING_FOR_APPROVAL");assertThat(count("approval_signals")).isEqualTo(1);assertThat(count("approval_signal_receipts")).isZero();
        drain();drain();assertThat(get(execution.id()).status()).isEqualTo(ExecutionState.SUCCEEDED);assertThat(get(execution.id()).deadlineAt()).isEqualTo(execution.deadlineAt());assertThat(count("synthetic_records")).isEqualTo(2);assertThat(count("execution_operation_receipts")).isEqualTo(2);assertThat(count("approval_signal_receipts")).isEqualTo(1);
        assertThat(executions.steps(owner.id(),owner.family(),org,execution.id())).allMatch(s->s.status().equals("SUCCEEDED"));
    }
    @Test void rejectionRetainsPreparationAndCannotBeRetriedOrDispatched(){var e=start();drain();decide(approver,request(e.id()),"REJECT");drain();assertThat(get(e.id()).status()).isEqualTo(ExecutionState.FAILED);assertThat(get(e.id()).errorCode()).isEqualTo("APPROVAL_REJECTED");assertThat(get(e.id()).retryEligible()).isFalse();assertThat(count("synthetic_records")).isEqualTo(1);denied(409,()->executions.retry(owner.id(),owner.family(),org,e.id(),get(e.id()).revision(),"Retry",UUID.randomUUID().toString()));}
    @Test void expiredApprovalIsDeniedAndReconciledWithoutResettingExecutionDeadline(){var e=start();drain();var r=request(e.id());clock.add(3600);denied(409,()->decide(approver,r,"APPROVE"));drain();assertThat(request(e.id()).status()).isEqualTo(ApprovalState.EXPIRED);assertThat(get(e.id()).status()).isEqualTo(ExecutionState.FAILED);assertThat(get(e.id()).deadlineAt()).isEqualTo(e.deadlineAt());assertThat(count("synthetic_records")).isEqualTo(1);}
    @Test void cancellationPreventsLateDecisionAndTerminalResumption(){var e=start();drain();var r=request(e.id());executions.cancel(owner.id(),owner.family(),org,e.id(),get(e.id()).revision(),"Cancel governed work");denied(409,()->decide(approver,r,"APPROVE"));drain();assertThat(request(e.id()).status()).isEqualTo(ApprovalState.CANCELLED);assertThat(get(e.id()).status()).isEqualTo(ExecutionState.CANCELLED);assertThat(count("synthetic_records")).isEqualTo(1);}
    @Test void policyChangesInvalidatePendingRequestsAndPreserveVersionSnapshots(){var e=start();drain();var r=request(e.id());var p=approvals.updatePolicy(owner.id(),owner.family(),org,0,true,600);assertThat(p.version()).isEqualTo(1);assertThat(count("approval_policy_versions")).isEqualTo(2);assertThat(request(e.id()).status()).isEqualTo(ApprovalState.INVALIDATED);assertThat(request(e.id()).policyVersion()).isZero();denied(409,()->decide(approver,r,"APPROVE"));drain();assertThat(get(e.id()).status()).isEqualTo(ExecutionState.FAILED);assertThat(count("synthetic_records")).isEqualTo(1);}
    @Test void nodeOmissionCannotBypassCentralRecordPolicy(){approvals.updatePolicy(owner.id(),owner.family(),org,0,true,3600);publish(GRAPH.replace("{\"id\":\"gate\",\"type\":\"approval\",\"typeVersion\":1,\"config\":{\"prompt\":\"Review exact internal record\"}},","").replace("{\"source\":\"prepare\",\"target\":\"gate\"},{\"source\":\"gate\",\"target\":\"protected\"}","{\"source\":\"prepare\",\"target\":\"protected\"}"));var e=start();drain();assertThat(count("synthetic_records")).isZero();var first=request(e.id());assertThat(first.actionNodeId()).isEqualTo("prepare");decide(approver,first,"APPROVE");drain();assertThat(count("synthetic_records")).isEqualTo(1);var second=request(e.id());assertThat(second.actionNodeId()).isEqualTo("protected");decide(approver,second,"APPROVE");drain();assertThat(get(e.id()).status()).isEqualTo(ExecutionState.SUCCEEDED);assertThat(count("approval_requests")).isEqualTo(2);assertThat(count("synthetic_records")).isEqualTo(2);}
    @Test void duplicateDecisionsAndUnauthorizedRolesCannotCreateAnotherSignal(){var e=start();drain();var r=request(e.id());var viewer=member("VIEWER");denied(403,()->decide(viewer,r,"APPROVE"));assertThat(approvals.get(viewer.id(),viewer.family(),org,r.id()).actionable()).isFalse();var admin=member("ADMIN");denied(403,()->approvals.updatePolicy(admin.id(),admin.family(),org,0,true,60));decide(approver,r,"APPROVE");denied(409,()->decide(approver,r,"REJECT"));assertThat(count("approval_decisions")).isEqualTo(1);assertThat(count("approval_signals")).isEqualTo(1);}
    @Test void decisionAuditFailureRollsBackDecisionSignalAndFinalization(){var e=start();drain();var r=request(e.id());auditFailure("APPROVAL_APPROVED");assertThatThrownBy(()->decide(approver,r,"APPROVE")).isInstanceOf(org.springframework.dao.DataAccessException.class);assertThat(request(e.id()).status()).isEqualTo(ApprovalState.PENDING);assertThat(count("approval_decisions")).isZero();assertThat(count("approval_signals")).isZero();assertThat(count("synthetic_records")).isEqualTo(1);}
    @Test void policyAuditFailureRollsBackVersionAndPendingInvalidation(){var e=start();drain();auditFailure("APPROVAL_POLICY_CHANGED");assertThatThrownBy(()->approvals.updatePolicy(owner.id(),owner.family(),org,0,true,60)).isInstanceOf(org.springframework.dao.DataAccessException.class);assertThat(approvals.policy(owner.id(),owner.family(),org).version()).isZero();assertThat(count("approval_policy_versions")).isEqualTo(1);assertThat(request(e.id()).status()).isEqualTo(ApprovalState.PENDING);assertThat(count("approval_signals")).isZero();}
    @Test void approvedDecisionCannotSurviveChangedPolicyBeforeConsumption(){var e=start();drain();decide(approver,request(e.id()),"APPROVE");approvals.updatePolicy(owner.id(),owner.family(),org,0,false,3600);drain();assertThat(get(e.id()).status()).isEqualTo(ExecutionState.FAILED);assertThat(get(e.id()).errorCode()).isEqualTo("POLICY_CHANGED");assertThat(request(e.id()).status()).isEqualTo(ApprovalState.APPROVED);assertThat(count("synthetic_records")).isEqualTo(1);}
    @Test void revokedApproverAccountPreventsResumeWithoutRollbackOnlyLostFailure(){var e=start();drain();decide(approver,request(e.id()),"APPROVE");operator.update("UPDATE users SET account_status='DISABLED' WHERE id=?",approver.id());drain();assertThat(get(e.id()).status()).isEqualTo(ExecutionState.FAILED);assertThat(get(e.id()).errorCode()).isEqualTo("APPROVER_REVOKED");assertThat(count("synthetic_records")).isEqualTo(1);}
    @Test void concurrentApproveAndRejectYieldOneImmutableDecision()throws Exception{var e=start();drain();var r=request(e.id());var pool=Executors.newFixedThreadPool(2);try{var a=pool.submit(()->raceDecision(r,"APPROVE"));var b=pool.submit(()->raceDecision(r,"REJECT"));assertThat(List.of(a.get(20,TimeUnit.SECONDS),b.get(20,TimeUnit.SECONDS))).containsExactlyInAnyOrder(200,409);}finally{pool.shutdownNow();}assertThat(count("approval_decisions")).isEqualTo(1);assertThat(count("approval_signals")).isEqualTo(1);drain();assertThat(get(e.id()).status().terminal()).isTrue();assertThat(count("synthetic_records")).isBetween(1L,2L);}
    int raceDecision(ApprovalService.View r,String outcome){try{decide(approver,r,outcome);return 200;}catch(DomainFailure conflict){return conflict.status();}}
}
