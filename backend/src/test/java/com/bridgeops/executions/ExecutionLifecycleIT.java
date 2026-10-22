package com.bridgeops.executions;

import com.bridgeops.executions.application.*;
import com.bridgeops.executions.application.ExecutionViews.*;
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
import java.util.concurrent.atomic.*;
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

@SpringBootTest(properties={"bridgeops.auth.enabled=true","bridgeops.auth.origin=https://bridgeops.invalid","bridgeops.auth.issuer=execution-test","bridgeops.auth.key-id=execution-test","bridgeops.executions.worker.enabled=false"})
@Testcontainers
class ExecutionLifecycleIT {
    static final TestAuthMaterial keys=new TestAuthMaterial();
    @Container static final PostgreSQLContainer postgres=new PostgreSQLContainer(DockerImageName.parse("postgres@sha256:77f585114c32fbca283dc835b0596f4e52b51b4c6662d7810b2f4084f60a1873").asCompatibleSubstituteFor("postgres")).withInitScript("organization-runtime.sql");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r){
        r.add("spring.datasource.url",postgres::getJdbcUrl);r.add("spring.datasource.username",()->"bridgeops_app");r.add("spring.datasource.password",()->"synthetic-runtime-fixture");r.add("spring.flyway.user",postgres::getUsername);r.add("spring.flyway.password",postgres::getPassword);
        r.add("bridgeops.auth.private-key",()->keys.directory.resolve("private.pem").toString());r.add("bridgeops.auth.public-key",()->keys.directory.resolve("public.pem").toString());r.add("bridgeops.auth.csrf-key",()->keys.directory.resolve("csrf.key").toString());
    }
    static class MutableClock extends Clock {
        Instant now=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId zone){return this;}public Instant instant(){return now;}void add(long seconds){now=now.plusSeconds(seconds);}
    }
    static class ControlledCapability extends SyntheticCapability {
        volatile String failNode;volatile FailureKind failure;AtomicInteger remaining=new AtomicInteger();volatile boolean interrupt;
        @Override public void prepare(Invocation call){if(call.node().equals(failNode)&&remaining.getAndDecrement()>0)throw new Failure(failure);}
        @Override public void afterCommit(Invocation call){if(interrupt){interrupt=false;throw new IllegalStateException("Controlled post-commit interruption");}}
        void reset(){failNode=null;failure=null;remaining.set(0);interrupt=false;}
    }
    @TestConfiguration static class Configuration {
        @Bean @Primary MutableClock controlledClock(){return new MutableClock();}
        @Bean @Primary ExecutionRandom controlledRandom(){return new ExecutionRandom(){@Override public double sample(){return 0;}};}
        @Bean @Primary ControlledCapability controlledCapability(){return new ControlledCapability();}
    }
    @Autowired ExecutionService executions;@Autowired ExecutionRuntime runtime;@Autowired ExecutionStore store;@Autowired WorkflowService workflows;
    @Autowired GraphDefinition graphs;@Autowired AccountPasswords passwords;@Autowired PasswordSessionService sessions;@Autowired OrganizationService organizations;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
    @Autowired com.bridgeops.organizations.application.TenantAuthorization workerAuthority;
    @Autowired com.bridgeops.workflows.application.WorkflowRuntime snapshots;
    @Autowired com.bridgeops.workflows.application.WorkflowDefinitions definitions;
    @Autowired com.bridgeops.audit.application.ExecutionAudit executionAudit;
    @Autowired ExecutionRandom randomness;
    @Autowired com.bridgeops.approvals.application.ApprovalRuntime approvals;
    @Autowired com.bridgeops.approvals.application.ApprovalService approvalCommands;
    @Autowired com.bridgeops.integrations.application.ConnectionService connections;
    @Autowired com.bridgeops.integrations.application.BusinessService business;
    @Autowired CapabilityDispatch capabilityGateway;
    @Autowired MutableClock clock;@Autowired ControlledCapability capability;@Autowired JdbcTemplate runtimeDb;
    JdbcTemplate operator;Actor owner;UUID org,workflow,version;ExecutionWorker worker;static String passwordHash;
    record Actor(UUID id,UUID family) {}
    static final String GRAPH="""
      {"schemaVersion":1,"nodes":[
      {"id":"finish","type":"end","typeVersion":1,"config":{}},
      {"id":"second","type":"syntheticAction","typeVersion":1,"config":{"operation":"record","message":"Second record"}},
      {"id":"start","type":"manualTrigger","typeVersion":1,"config":{}},
      {"id":"first","type":"syntheticAction","typeVersion":1,"config":{"operation":"record","message":"First record"}}],
      "edges":[{"source":"second","target":"finish"},{"source":"start","target":"first"},{"source":"first","target":"second"}]}
      """;
    @BeforeEach void fixtures(){
        operator=new JdbcTemplate(new DriverManagerDataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword()));
        operator.execute("TRUNCATE users,installation_state,authentication_throttles CASCADE");operator.update("INSERT INTO installation_state(singleton_id) VALUES(1)");clock.now=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);capability.reset();
        if(passwordHash==null)passwordHash=passwords.hash("execution-disposable-password");owner=account("owner@example.invalid");
        operator.update("INSERT INTO user_grants VALUES(?,'organization.create',?)",owner.id(),java.sql.Timestamp.from(clock.instant()));
        org=organizations.create(owner.id(),owner.family(),"Execution test","execution-test").id();
        workflow=workflows.create(owner.id(),owner.family(),org,"Synthetic chain","",graphs.parse(GRAPH)).workflow().id();version=workflows.publish(owner.id(),owner.family(),org,workflow,0).version().id();
        worker=new ExecutionWorker(runtime,capability,4,2,30,1000);
    }
    Actor account(String email){UUID id=UUID.randomUUID();var now=java.sql.Timestamp.from(clock.instant());
        operator.update("INSERT INTO users(id,email,normalized_email,password_hash,display_name,account_status,created_at,email_verified_at) VALUES(?,?,?,?,?,'ACTIVE',?,?)",id,email,email,passwordHash,"Synthetic fixture",now,now);
        var grant=(Granted)sessions.authenticate(email,"execution-disposable-password");return new Actor(id,grant.familyId());
    }
    Actor member(String role){Actor a=account(role.toLowerCase(Locale.ROOT)+"@example.invalid");UUID member=UUID.randomUUID();
        operator.update("INSERT INTO organization_memberships(id,organization_id,user_id,status,joined_at) VALUES(?,?,?,'ACTIVE',?)",member,org,a.id(),java.sql.Timestamp.from(clock.instant()));
        operator.update("INSERT INTO membership_roles SELECT ?,?,id FROM roles WHERE organization_id=? AND name=?",org,member,org,role);return a;
    }
    Submission start(String key){return executions.start(owner.id(),owner.family(),org,workflow,version,graphs.parse("{}"),0,key);}
    Summary get(UUID id){return executions.get(owner.id(),owner.family(),org,id);}
    ExecutionStore.Claim claim(){return runtime.claim(UUID.randomUUID(),4,2,30);}
    void drain(){for(int i=0;i<100;i++){runtime.reconcile();var c=claim();if(c==null)return;worker.dispatch(c);}throw new AssertionError("Worker did not quiesce");}
    long count(String table){return operator.queryForObject("SELECT count(*) FROM "+table,Long.class);}
    void failure(String node,SyntheticCapability.FailureKind kind,int times){capability.failNode=node;capability.failure=kind;capability.remaining.set(times);}
    static void denied(int status,Runnable work){assertThatThrownBy(work::run).isInstanceOfSatisfying(DomainFailure.class,e->assertThat(e.status()).isEqualTo(status));}
    @Test void executesEdgeOrderAndPinsOldVersionWithPersistedResultsAttemptsAndTimeline(){
        var e=start("edge-order-key").execution();workflows.save(owner.id(),owner.family(),org,workflow,1,"Later","",graphs.parse(GRAPH.replace("First record","Changed later")));workflows.publish(owner.id(),owner.family(),org,workflow,2);
        drain();var done=get(e.id());assertThat(done.status()).isEqualTo(ExecutionState.SUCCEEDED);assertThat(done.versionId()).isEqualTo(version);
        assertThat(executions.steps(owner.id(),owner.family(),org,e.id())).extracting(Step::nodeId).containsExactly("start","first","second","finish");
        assertThat(executions.steps(owner.id(),owner.family(),org,e.id())).allMatch(s->s.status().equals("SUCCEEDED"));
        assertThat(operator.queryForList("SELECT message FROM synthetic_records ORDER BY recorded_at,logical_operation_id",String.class)).containsExactlyInAnyOrder("First record","Second record");
        assertThat(executions.attempts(owner.id(),owner.family(),org,e.id(),0,100).totalElements()).isEqualTo(4);
        assertThat(executions.events(owner.id(),owner.family(),org,e.id(),0,100).items()).extracting(Event::code).contains("EXECUTION_QUEUED","RECORD_COMMITTED","EXECUTION_SUCCEEDED");
        assertThat(count("execution_operation_receipts")).isEqualTo(2);assertThat(done.completedAt()).isNotNull();
    }
    @Test void exactStartReplayAndChangedVersionOrInputConflict(){
        var e=start("repeat-start-key").execution();assertThat(start("repeat-start-key").execution().id()).isEqualTo(e.id());
        denied(409,()->executions.start(owner.id(),owner.family(),org,workflow,version,graphs.parse("{\"unexpected\":true}"),0,"repeat-start-key"));
        var v2=workflows.publish(owner.id(),owner.family(),org,workflow,1).version().id();
        denied(409,()->executions.start(owner.id(),owner.family(),org,workflow,v2,graphs.parse("{}"),0,"repeat-start-key"));
        assertThat(count("workflow_executions")).isEqualTo(1);assertThat(count("execution_work_items")).isEqualTo(1);
        drain();assertThat(start("repeat-start-key").execution().status()).isEqualTo(ExecutionState.SUCCEEDED);
    }
    @Test void concurrentIdenticalSubmissionsCreateOneExecutionAndWorkItem()throws Exception{
        var pool=Executors.newFixedThreadPool(2);try{
            var a=pool.submit(()->start("concurrent-start-key").execution().id());var b=pool.submit(()->start("concurrent-start-key").execution().id());assertThat(a.get(20,TimeUnit.SECONDS)).isEqualTo(b.get(20,TimeUnit.SECONDS));
        }finally{pool.shutdownNow();}assertThat(count("workflow_executions")).isEqualTo(1);assertThat(count("execution_work_items")).isEqualTo(1);
    }
    @Test void unpublishedInvalidInputAndApprovalDefinitionsNeverCreateExecution(){
        UUID unpublished=workflows.create(owner.id(),owner.family(),org,"Unpublished","",graphs.parse("{}")).workflow().id();
        denied(422,()->executions.start(owner.id(),owner.family(),org,unpublished,null,graphs.parse("{}"),0,"unpublished-key"));
        denied(422,()->executions.start(owner.id(),owner.family(),org,workflow,version,graphs.parse("[]"),0,"invalid-input-key"));
        workflows.save(owner.id(),owner.family(),org,workflow,1,"Approval","",graphs.parse(GRAPH.replace("\"type\":\"syntheticAction\",\"typeVersion\":1,\"config\":{\"operation\":\"record\",\"message\":\"Second record\"}","\"type\":\"approval\",\"typeVersion\":1,\"config\":{\"prompt\":\"Review\"}")));
        UUID approvalVersion=workflows.publish(owner.id(),owner.family(),org,workflow,2).version().id();
        denied(422,()->executions.start(owner.id(),owner.family(),org,workflow,approvalVersion,graphs.parse("{}"),0,"approval-key"));assertThat(count("workflow_executions")).isZero();
    }
    @Test void claimsAreExclusiveGloballyAndPerTenantAndReleaseAfterEachStep()throws Exception{
        start("claims-first-key");start("claims-second-key");start("claims-third-key");
        var pool=Executors.newFixedThreadPool(2);try{var a=pool.submit(this::claim);var b=pool.submit(this::claim);var first=a.get(20,TimeUnit.SECONDS);var second=b.get(20,TimeUnit.SECONDS);
            if(first==null)first=claim();if(second==null)second=claim();assertThat(first).isNotNull();assertThat(second).isNotNull();assertThat(first.execution()).isNotEqualTo(second.execution());assertThat(claim()).isNull();
            worker.dispatch(first);assertThat(claim()).isNotNull();
        }finally{pool.shutdownNow();}
    }
    @Test void crashAfterClaimRecoversWithNewFenceAndRejectsStaleWrites(){
        var e=start("claim-crash-key").execution();var stale=claim();clock.add(31);assertThat(runtime.reconcile()).isEqualTo(1);var fresh=claim();assertThat(fresh.token()).isGreaterThan(stale.token());
        assertThat(runtime.prepare(stale)).isNull();assertThat(runtime.renew(stale,30)).isFalse();worker.dispatch(fresh);drain();assertThat(get(e.id()).status()).isEqualTo(ExecutionState.SUCCEEDED);
    }
    @Test void crashAfterPrepareRetainsInterruptedAttemptAndCompletesAfterLeaseRecovery(){
        var e=start("prepare-crash-key").execution();var c=claim();var d=runtime.prepare(c);assertThat(d).isNotNull();clock.add(31);runtime.reconcile();assertThat(runtime.complete(d)).isFalse();drain();
        assertThat(get(e.id()).status()).isEqualTo(ExecutionState.SUCCEEDED);assertThat(executions.attempts(owner.id(),owner.family(),org,e.id(),0,100).items()).extracting(Attempt::outcome).contains("INTERRUPTED","SUCCEEDED");
    }
    @Test void interruptionAfterCommittedEffectDoesNotDuplicateReceiptOrEffect(){
        var e=start("effect-crash-key").execution();worker.dispatch(claim());capability.interrupt=true;worker.dispatch(claim());assertThat(count("synthetic_records")).isEqualTo(1);
        clock.add(31);runtime.reconcile();drain();assertThat(get(e.id()).status()).isEqualTo(ExecutionState.SUCCEEDED);assertThat(count("synthetic_records")).isEqualTo(2);assertThat(count("execution_operation_receipts")).isEqualTo(2);
    }
    @Test void eligibleTransientFailurePersistsRetryThenSucceedsWithSameOperation(){
        var e=start("transient-key").execution();failure("first",SyntheticCapability.FailureKind.TRANSIENT,1);drain();assertThat(get(e.id()).status()).isEqualTo(ExecutionState.WAITING_FOR_RETRY);assertThat(count("synthetic_records")).isZero();
        assertThat(executions.steps(owner.id(),owner.family(),org,e.id()).stream().filter(s1->s1.nodeId().equals("first")).findFirst().orElseThrow().retryAt()).isEqualTo(clock.instant().plusSeconds(1));
        clock.add(2);drain();assertThat(get(e.id()).status()).isEqualTo(ExecutionState.SUCCEEDED);assertThat(count("synthetic_records")).isEqualTo(2);
        var attempts=executions.attempts(owner.id(),owner.family(),org,e.id(),0,100).items().stream().filter(a->a.nodeId().equals("first")).toList();assertThat(attempts).extracting(Attempt::outcome).containsExactly("TRANSIENT_FAILURE","SUCCEEDED");
    }
    @Test void exhaustedTransientFailureCanRetryNewExecutionWithoutRepeatingEarlierEffects(){
        var e=start("exhausted-key").execution();failure("second",SyntheticCapability.FailureKind.TRANSIENT,3);drain();clock.add(2);drain();clock.add(3);drain();var failed=get(e.id());
        assertThat(failed.status()).isEqualTo(ExecutionState.FAILED);assertThat(failed.retryEligible()).isTrue();assertThat(count("synthetic_records")).isEqualTo(1);
        var retry=executions.retry(owner.id(),owner.family(),org,e.id(),failed.revision(),"Retry transient failure","manual-retry-key").execution();assertThat(retry.retryOfExecutionId()).isEqualTo(e.id());assertThat(retry.versionId()).isEqualTo(version);
        drain();assertThat(get(retry.id()).status()).isEqualTo(ExecutionState.SUCCEEDED);assertThat(count("synthetic_records")).isEqualTo(2);assertThat(get(e.id()).status()).isEqualTo(ExecutionState.FAILED);
        assertThat(executions.events(owner.id(),owner.family(),org,retry.id(),0,100).items()).extracting(Event::code).contains("RECEIPT_REUSED");
    }
    @Test void permanentFailurePreservesPartialProgressAndCannotRetry(){
        var e=start("permanent-key").execution();failure("second",SyntheticCapability.FailureKind.PERMANENT,1);drain();var failed=get(e.id());assertThat(failed.status()).isEqualTo(ExecutionState.FAILED);assertThat(failed.retryEligible()).isFalse();
        assertThat(executions.steps(owner.id(),owner.family(),org,e.id())).extracting(Step::status).containsExactly("SUCCEEDED","SUCCEEDED","FAILED","SKIPPED");
        denied(409,()->executions.retry(owner.id(),owner.family(),org,e.id(),failed.revision(),"Not eligible","retry-permanent-key"));assertThat(count("synthetic_records")).isEqualTo(1);
    }
    @Test void ambiguousOutcomeIsFailedForReconciliationWithoutAutomaticRetry(){
        var e=start("ambiguous-key").execution();failure("first",SyntheticCapability.FailureKind.AMBIGUOUS,1);drain();assertThat(get(e.id()).errorCode()).isEqualTo("RECONCILIATION_REQUIRED");assertThat(get(e.id()).retryEligible()).isFalse();assertThat(count("synthetic_records")).isZero();
    }
    @Test void scheduledStartSurvivesFreshRuntimeDispatchAndConsumesOriginalDeadline(){
        var e=executions.start(owner.id(),owner.family(),org,workflow,version,graphs.parse("{}"),60,"delay-start-key").execution();drain();assertThat(get(e.id()).status()).isEqualTo(ExecutionState.WAITING_FOR_DELAY);assertThat(claim()).isNull();
        clock.add(61);
        var restarted=new ExecutionRuntime(store,transactions,workerAuthority,snapshots,definitions,executions,executionAudit,clock,randomness,approvals);
        var restartedWorker=new ExecutionWorker(restarted,capability,4,2,30,1000);
        for(int i=0;i<20;i++){restarted.reconcile();var work=restarted.claim(UUID.randomUUID(),4,2,30);if(work==null)break;restartedWorker.dispatch(work);}
        assertThat(get(e.id()).status()).isEqualTo(ExecutionState.SUCCEEDED);assertThat(get(e.id()).deadlineAt()).isEqualTo(e.deadlineAt());
    }
    @Test void queuedDeadlineExpiresWithoutDispatchOrReset(){
        var e=start("queued-deadline-key").execution();clock.add(86401);runtime.reconcile();assertThat(store.get(org,e.id(),false).error()).isEqualTo("DEADLINE_EXCEEDED");assertThat(claim()).isNull();assertThat(count("synthetic_records")).isZero();assertThat(store.get(org,e.id(),false).deadline()).isEqualTo(e.deadlineAt());
    }
    @Test void runningAndRetryWaitingDeadlineCannotProduceLateEffects(){
        var e=start("running-deadline-key").execution();worker.dispatch(claim());var c=claim();var d=runtime.prepare(c);clock.add(86401);assertThat(runtime.complete(d)).isFalse();runtime.reconcile();assertThat(store.get(org,e.id(),false).error()).isEqualTo("DEADLINE_EXCEEDED");assertThat(count("synthetic_records")).isZero();
    }
    @Test void deadlineReconciliationIncludesPersistedRetryWait(){
        var e=start("retry-deadline-key").execution();failure("first",SyntheticCapability.FailureKind.TRANSIENT,1);drain();clock.add(86401);runtime.reconcile();assertThat(store.get(org,e.id(),false).error()).isEqualTo("DEADLINE_EXCEEDED");assertThat(count("synthetic_records")).isZero();
    }
    @Test void cancellationBeforeQueuedDispatchInvalidatesClaimsAndTerminalNeverResumes(){
        var e=start("cancel-queued-key").execution();var c=claim();executions.cancel(owner.id(),owner.family(),org,e.id(),e.revision(),"Stop requested");assertThat(runtime.prepare(c)).isNull();clock.add(31);runtime.reconcile();assertThat(claim()).isNull();assertThat(get(e.id()).status()).isEqualTo(ExecutionState.CANCELLED);assertThat(count("synthetic_records")).isZero();
    }
    @Test void cancelRunningRetainsEarlierEffectsAndRejectsInFlightCommit(){
        var e=start("cancel-running-key").execution();worker.dispatch(claim());worker.dispatch(claim());var c=claim();var d=runtime.prepare(c);var current=get(e.id());executions.cancel(owner.id(),owner.family(),org,e.id(),current.revision(),"Stop after partial result");assertThat(runtime.complete(d)).isFalse();assertThat(count("synthetic_records")).isEqualTo(1);assertThat(get(e.id()).status()).isEqualTo(ExecutionState.CANCELLED);
    }
    @Test void cancelRetryAndScheduledWaitNeverDispatchesDueWork(){
        var e=start("cancel-retry-key").execution();failure("first",SyntheticCapability.FailureKind.TRANSIENT,1);drain();executions.cancel(owner.id(),owner.family(),org,e.id(),get(e.id()).revision(),"Stop retry");clock.add(3);drain();assertThat(get(e.id()).status()).isEqualTo(ExecutionState.CANCELLED);assertThat(count("synthetic_records")).isZero();
        var wait=executions.start(owner.id(),owner.family(),org,workflow,version,graphs.parse("{}"),60,"cancel-delay-key").execution();drain();executions.cancel(owner.id(),owner.family(),org,wait.id(),get(wait.id()).revision(),"Stop wait");clock.add(61);drain();assertThat(get(wait.id()).status()).isEqualTo(ExecutionState.CANCELLED);
    }
    @Test void livePermissionsConcealForeignResourcesAndWorkerRechecksRevokedActor(){
        var e=start("permissions-key").execution();Actor viewer=member("VIEWER"),builder=member("BUILDER"),op=member("OPERATOR");
        assertThat(executions.get(viewer.id(),viewer.family(),org,e.id()).id()).isEqualTo(e.id());
        denied(403,()->executions.start(builder.id(),builder.family(),org,workflow,version,graphs.parse("{}"),0,"builder-denied-key"));
        denied(403,()->executions.cancel(viewer.id(),viewer.family(),org,e.id(),0,"Denied"));
        UUID other=organizations.create(owner.id(),owner.family(),"Other","other-execution").id();
        denied(404,()->executions.get(owner.id(),owner.family(),other,e.id()));denied(404,()->executions.steps(owner.id(),owner.family(),other,e.id()));denied(404,()->executions.events(owner.id(),owner.family(),other,e.id(),0,25));denied(404,()->executions.attempts(owner.id(),owner.family(),other,e.id(),0,25));denied(404,()->executions.cancel(owner.id(),owner.family(),other,e.id(),0,"Foreign"));
        var submitted=executions.start(op.id(),op.family(),org,workflow,version,graphs.parse("{}"),0,"operator-start-key").execution();operator.update("UPDATE organization_memberships SET status='SUSPENDED' WHERE organization_id=? AND user_id=?",org,op.id());drain();assertThat(get(submitted.id()).errorCode()).isEqualTo("PERMISSION_REVOKED");
    }
    @Test void auditFailureRollsBackStartAndCancellation(){
        var e=start("audit-before-key").execution();operator.execute("CREATE FUNCTION fail_execution_audit() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.resource_type='EXECUTION' THEN RAISE EXCEPTION 'controlled audit failure'; END IF; RETURN NEW; END $$");operator.execute("CREATE TRIGGER fail_execution_audit BEFORE INSERT ON organization_audit_events FOR EACH ROW EXECUTE FUNCTION fail_execution_audit()");
        try{assertThatThrownBy(()->start("audit-failure-key")).isInstanceOf(RuntimeException.class);assertThat(count("workflow_executions")).isEqualTo(1);assertThatThrownBy(()->executions.cancel(owner.id(),owner.family(),org,e.id(),0,"Must roll back")).isInstanceOf(RuntimeException.class);assertThat(get(e.id()).status()).isEqualTo(ExecutionState.QUEUED);assertThat(count("execution_attempts")).isZero();}
        finally{operator.execute("DROP TRIGGER fail_execution_audit ON organization_audit_events");operator.execute("DROP FUNCTION fail_execution_audit()");}
    }
    @Test void immutableAttemptsEventsAndReceiptsDenyRuntimeMutation(){
        var e=start("immutable-history-key").execution();drain();for(String table:List.of("execution_attempts","execution_events","synthetic_records","execution_operation_receipts"))assertThatThrownBy(()->runtimeDb.update("DELETE FROM "+table)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(get(e.id()).status()).isEqualTo(ExecutionState.SUCCEEDED);denied(409,()->executions.cancel(owner.id(),owner.family(),org,e.id(),get(e.id()).revision(),"Terminal"));assertThat(runtime.reconcile()).isZero();
    }
    @Test void scheduledWaitDeadlineExpiresWithoutResetOrDispatch(){
        var e=executions.start(owner.id(),owner.family(),org,workflow,version,graphs.parse("{}"),3600,"waiting-deadline-key").execution();drain();assertThat(get(e.id()).status()).isEqualTo(ExecutionState.WAITING_FOR_DELAY);clock.add(86401);runtime.reconcile();assertThat(store.get(org,e.id(),false).error()).isEqualTo("DEADLINE_EXCEEDED");assertThat(count("synthetic_records")).isZero();assertThat(claim()).isNull();
    }
    @Test void runningCancellationRaceIsConditionalAndCannotDispatchAfterCommit()throws Exception{
        var e=start("cancel-race-key").execution();worker.dispatch(claim());var c=claim();var dispatch=runtime.prepare(c);long revision=get(e.id()).revision();
        var pool=Executors.newFixedThreadPool(2);try{
            var completed=pool.submit(()->runtime.complete(dispatch));var cancelled=pool.submit(()->{try{executions.cancel(owner.id(),owner.family(),org,e.id(),revision,"Concurrent stop");return 200;}catch(DomainFailure conflict){return conflict.status();}});
            completed.get(20,TimeUnit.SECONDS);int result=cancelled.get(20,TimeUnit.SECONDS);assertThat(result).isIn(200,409);if(result==409)executions.cancel(owner.id(),owner.family(),org,e.id(),get(e.id()).revision(),"Stop after committed result");
        }finally{pool.shutdownNow();}
        runtime.acknowledge(c);clock.add(31);runtime.reconcile();drain();assertThat(get(e.id()).status()).isEqualTo(ExecutionState.CANCELLED);assertThat(count("synthetic_records")).isBetween(0L,1L);assertThat(runtime.complete(dispatch)).isFalse();
    }
    @Test void disabledActorFailsSafelyButBrowserLogoutAloneDoesNotCancelDurableWork(){
        var e=start("logout-durable-key").execution();sessions.revokeAll(owner.id());drain();assertThat(store.get(org,e.id(),false).state()).isEqualTo(ExecutionState.SUCCEEDED);
        var renewed=(Granted)sessions.authenticate("owner@example.invalid","execution-disposable-password");owner=new Actor(owner.id(),renewed.familyId());var disabled=start("disabled-worker-key").execution();operator.update("UPDATE users SET account_status='DISABLED' WHERE id=?",owner.id());drain();assertThat(store.get(org,disabled.id(),false).error()).isEqualTo("PERMISSION_REVOKED");assertThat(count("synthetic_records")).isEqualTo(2);
    }
    @Test void retryAuditFailureLeavesOriginalTerminalAndNoPartialLinkedExecution(){
        var e=start("retry-audit-source-key").execution();failure("second",SyntheticCapability.FailureKind.TRANSIENT,3);drain();clock.add(2);drain();clock.add(3);drain();var failed=get(e.id());
        operator.execute("CREATE FUNCTION fail_retry_audit() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.action='EXECUTION_RETRIED' THEN RAISE EXCEPTION 'controlled retry audit failure'; END IF; RETURN NEW; END $$");operator.execute("CREATE TRIGGER fail_retry_audit BEFORE INSERT ON organization_audit_events FOR EACH ROW EXECUTE FUNCTION fail_retry_audit()");
        try{assertThatThrownBy(()->executions.retry(owner.id(),owner.family(),org,e.id(),failed.revision(),"Retry transient failure","retry-audit-key")).isInstanceOf(RuntimeException.class);assertThat(count("workflow_executions")).isEqualTo(1);assertThat(get(e.id()).status()).isEqualTo(ExecutionState.FAILED);assertThat(count("synthetic_records")).isEqualTo(1);}
        finally{operator.execute("DROP TRIGGER fail_retry_audit ON organization_audit_events");operator.execute("DROP FUNCTION fail_retry_audit()");}
    }
    @Test void terminalDatabaseGuardAndTenantQualifiedWorkKeysRejectInvalidWrites(){
        var e=start("database-guards-key").execution();drain();assertThatThrownBy(()->runtimeDb.update("UPDATE workflow_executions SET status='QUEUED',revision=revision+1,completed_at=NULL WHERE id=?",e.id())).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        UUID other=organizations.create(owner.id(),owner.family(),"Other","foreign-queue-key").id();assertThatThrownBy(()->runtimeDb.update("INSERT INTO execution_work_items(organization_id,execution_id,due_at,state) VALUES(?,?,now(),'READY')",other,e.id())).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(()->operator.update("UPDATE execution_attempts SET outcome='PERMANENT_FAILURE' WHERE execution_id=?",e.id())).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }
    @Test void reconciliationLocksActorBeforeOrganizationEvenForExpiredWork()throws Exception{
        var e=start("reconcile-lock-order-key").execution();clock.add(86401);var pool=Executors.newSingleThreadExecutor();
        try(var connection=operator.getDataSource().getConnection()){
            connection.setAutoCommit(false);try(var statement=connection.prepareStatement("SELECT id FROM users WHERE id=? FOR UPDATE")){statement.setObject(1,owner.id());statement.executeQuery().close();}
            var result=pool.submit(()->runtime.reconcile());
            try{
                long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);boolean waiting=false;
                while(System.nanoTime()<until){waiting=Boolean.TRUE.equals(operator.queryForObject("SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE wait_event_type='Lock' AND query LIKE 'SELECT id FROM users WHERE id=%FOR UPDATE%')",Boolean.class));if(waiting)break;Thread.sleep(10);}
                assertThat(waiting).isTrue();assertThat(operator.queryForObject("SELECT id FROM organizations WHERE id=? FOR UPDATE NOWAIT",UUID.class,org)).isEqualTo(org);
            }finally{connection.commit();}
            assertThat(result.get(20,TimeUnit.SECONDS)).isEqualTo(1);assertThat(store.get(org,e.id(),false).error()).isEqualTo("DEADLINE_EXCEEDED");
        }finally{pool.shutdownNow();}
    }

    @Test void archivedTenantStillTerminatesRevokedAndExpiredWorkWithoutDispatch(){
        var denied=start("archived-tenant-denial-key").execution();
        operator.update("UPDATE organizations SET status='ARCHIVED' WHERE id=?",org);drain();
        assertThat(store.get(org,denied.id(),false).error()).isEqualTo("PERMISSION_REVOKED");assertThat(count("synthetic_records")).isZero();
        operator.update("UPDATE organizations SET status='ACTIVE' WHERE id=?",org);var expired=start("archived-tenant-deadline-key").execution();
        operator.update("UPDATE organizations SET status='ARCHIVED' WHERE id=?",org);clock.add(86401);runtime.reconcile();
        assertThat(store.get(org,expired.id(),false).error()).isEqualTo("DEADLINE_EXCEEDED");assertThat(claim()).isNull();
    }

    @Test void globalAdmissionBoundsClaimsAcrossTenantsWithoutLettingOneTenantTakeAllSlots(){
        start("global-admission-one");start("global-admission-two");start("global-admission-three");
        for(int tenant=0;tenant<2;tenant++){
            UUID another=organizations.create(owner.id(),owner.family(),"Another tenant","global-admission-"+tenant).id();
            UUID draft=workflows.create(owner.id(),owner.family(),another,"Internal chain","",graphs.parse(GRAPH)).workflow().id();UUID pinned=workflows.publish(owner.id(),owner.family(),another,draft,0).version().id();
            for(int i=0;i<2;i++)executions.start(owner.id(),owner.family(),another,draft,pinned,graphs.parse("{}"),0,"global-admission-"+tenant+"-"+i);
        }
        List<ExecutionStore.Claim> claims=new ArrayList<>();for(int i=0;i<4;i++)claims.add(claim());assertThat(claims).doesNotContainNull();assertThat(claim()).isNull();
        assertThat(claims.stream().map(ExecutionStore.Claim::organization).distinct().count()).isGreaterThanOrEqualTo(2);
        for(var tenant:claims.stream().map(ExecutionStore.Claim::organization).distinct().toList())assertThat(claims.stream().filter(c->c.organization().equals(tenant)).count()).isLessThanOrEqualTo(2);
        worker.dispatch(claims.getFirst());assertThat(claim()).isNotNull();
    }
    void publishV2(String graph){workflow=workflows.create(owner.id(),owner.family(),org,"Bounded DAG","",graphs.parse(graph)).workflow().id();version=workflows.publish(owner.id(),owner.family(),org,workflow,0).version().id();}
    Submission startV2(String input,String key){return executions.start(owner.id(),owner.family(),org,workflow,version,graphs.parse(input),0,key,true);}
    String v2(){return com.bridgeops.workflows.GraphV2DefinitionTest.GRAPH;}
    String delayV2(int seconds){return v2().replace("\"type\":\"syntheticAction\",\"typeVersion\":1,\"config\":{\"operation\":\"record\",\"message\":\"Work exists\"}","\"type\":\"delay\",\"typeVersion\":1,\"config\":{\"seconds\":"+seconds+"}");}
    @Test void v2ChoosesOnlyOneBranchAndPersistsInputsRoutesOutputsAndSkippedState(){
        publishV2(v2());
        for(int count:new int[]{1,0}){
            var e=startV2("{\"count\":"+count+"}","dag-branch-"+count).execution();drain();assertThat(get(e.id()).status()).isEqualTo(ExecutionState.SUCCEEDED);
            var steps=executions.steps(owner.id(),owner.family(),org,e.id());String selected=count==1?"positive":"empty",skipped=count==1?"empty":"positive";
            assertThat(steps.stream().filter(s->s.nodeId().equals(selected)).findFirst().orElseThrow().output().toString()).contains("recorded");
            assertThat(steps.stream().filter(s->s.nodeId().equals(skipped)).findFirst().orElseThrow()).satisfies(s->{assertThat(s.status()).isEqualTo("SKIPPED");assertThat(s.attemptCount()).isZero();assertThat(s.errorCode()).isEqualTo("BRANCH_NOT_SELECTED");});
            assertThat(operator.queryForObject("SELECT next_node_id FROM execution_step_routes WHERE organization_id=? AND execution_id=? AND node_id='choose'",String.class,org,e.id())).isEqualTo(selected);
            assertThat(operator.queryForObject("SELECT count(*) FROM execution_step_inputs WHERE execution_id=?",Long.class,e.id())).isEqualTo(5);
            assertThat(executions.attempts(owner.id(),owner.family(),org,e.id(),0,100).totalElements()).isEqualTo(5);assertThat(executions.events(owner.id(),owner.family(),org,e.id(),0,100).items()).extracting(Event::code).contains("BRANCH_SKIPPED","EXECUTION_SUCCEEDED");
        }
        assertThat(count("synthetic_records")).isEqualTo(2);
    }
    @Test void v2RequiresAcknowledgementAndCanonicalTypedInputBeforeCreatingDurableWork(){
        publishV2(v2().replace("\"count\":\"INTEGER\"","\"count\":\"INTEGER\",\"flag\":\"BOOLEAN\""));
        denied(422,()->executions.start(owner.id(),owner.family(),org,workflow,version,graphs.parse("{\"count\":1,\"flag\":true}"),0,"dag-ack-key",false));assertThat(count("workflow_executions")).isZero();assertThat(count("execution_work_items")).isZero();
        var a=startV2("{\"count\":1,\"flag\":true}","dag-input-key");var b=startV2("{\"flag\":true,\"count\":1}","dag-input-key");assertThat(b.replay()).isTrue();assertThat(b.execution().id()).isEqualTo(a.execution().id());denied(409,()->startV2("{\"flag\":true,\"count\":0}","dag-input-key"));
        denied(422,()->startV2("{\"flag\":true,\"count\":\"1\"}","dag-input-other"));assertThat(count("workflow_executions")).isEqualTo(1);
    }
    @Test void concurrentV2StartSubmissionsCreateOneExecutionAndOneQueueItem()throws Exception{
        publishV2(v2());try(var pool=Executors.newFixedThreadPool(2)){var a=pool.submit(()->startV2("{\"count\":1}","dag-concurrent"));var b=pool.submit(()->startV2("{\"count\":1}","dag-concurrent"));var x=a.get(15,TimeUnit.SECONDS);var y=b.get(15,TimeUnit.SECONDS);assertThat(x.execution().id()).isEqualTo(y.execution().id());assertThat(List.of(x.replay(),y.replay())).containsExactlyInAnyOrder(false,true);}assertThat(count("workflow_executions")).isEqualTo(1);assertThat(count("execution_work_items")).isEqualTo(1);
    }
    @Test void v2DelayIsPersistedReleasesLeaseAndResumesAfterReconstructionWithoutResettingDeadline(){
        publishV2(delayV2(10));var e=startV2("{\"count\":1}","dag-delay-key").execution();drain();assertThat(get(e.id()).status()).isEqualTo(ExecutionState.WAITING_FOR_DELAY);assertThat(claim()).isNull();assertThat(operator.queryForObject("SELECT lease_owner IS NULL FROM execution_work_items WHERE execution_id=?",Boolean.class,e.id())).isTrue();assertThat(count("execution_step_delays")).isEqualTo(1);
        assertThat(executions.steps(owner.id(),owner.family(),org,e.id()).stream().filter(s->s.nodeId().equals("empty")).findFirst().orElseThrow().status()).isEqualTo("SKIPPED");
        var restarted=new ExecutionRuntime(store,transactions,workerAuthority,snapshots,definitions,executions,executionAudit,clock,randomness,approvals);var restartedWorker=new ExecutionWorker(restarted,capability,4,2,30,1000);clock.add(11);
        for(int i=0;i<20;i++){restarted.reconcile();var c=restarted.claim(UUID.randomUUID(),4,2,30);if(c==null)break;restartedWorker.dispatch(c);}
        assertThat(get(e.id()).status()).isEqualTo(ExecutionState.SUCCEEDED);assertThat(get(e.id()).deadlineAt()).isEqualTo(e.deadlineAt());assertThat(executions.steps(owner.id(),owner.family(),org,e.id()).stream().filter(s->s.nodeId().equals("positive")).findFirst().orElseThrow().attemptCount()).isEqualTo(1);assertThat(count("synthetic_records")).isZero();
    }
    @Test void cancellingV2ScheduledDelayRecordsAttemptAndNeverDispatchesAfterDueTime(){
        publishV2(delayV2(10));var e=startV2("{\"count\":1}","dag-delay-cancel").execution();drain();var waiting=get(e.id());executions.cancel(owner.id(),owner.family(),org,e.id(),waiting.revision(),"Cancel scheduled wait");clock.add(11);drain();assertThat(get(e.id()).status()).isEqualTo(ExecutionState.CANCELLED);assertThat(count("synthetic_records")).isZero();assertThat(executions.attempts(owner.id(),owner.family(),org,e.id(),0,100).items()).anyMatch(a->a.nodeId().equals("positive")&&a.outcome().equals("CANCELLED"));assertThat(claim()).isNull();
    }
    @Test void v2AbsoluteDeadlineExpiresDuringPersistedWait(){
        publishV2(delayV2(3600));var e=startV2("{\"count\":1}","dag-delay-deadline").execution();drain();clock.add(86401);runtime.reconcile();assertThat(get(e.id()).status()).isEqualTo(ExecutionState.FAILED);assertThat(get(e.id()).errorCode()).isEqualTo("DEADLINE_EXCEEDED");assertThat(get(e.id()).deadlineAt()).isEqualTo(e.deadlineAt());assertThat(claim()).isNull();assertThat(count("synthetic_records")).isZero();
    }
    @Test void v2TypeMismatchIsPermanentAndRetainsEarlierOutputs(){
        publishV2(v2().replace("\"count\":\"INTEGER\"","\"count\":\"STRING\""));var e=startV2("{\"count\":\"one\"}","dag-condition-type").execution();drain();assertThat(get(e.id()).status()).isEqualTo(ExecutionState.FAILED);assertThat(get(e.id()).errorCode()).isEqualTo("CONDITION_TYPE_MISMATCH");assertThat(get(e.id()).retryEligible()).isFalse();
        assertThat(executions.steps(owner.id(),owner.family(),org,e.id()).stream().filter(s->Set.of("start","copy").contains(s.nodeId())).toList()).allMatch(s->s.status().equals("SUCCEEDED"));assertThat(executions.attempts(owner.id(),owner.family(),org,e.id(),0,100).items()).anyMatch(a->a.nodeId().equals("choose")&&a.outcome().equals("PERMANENT_FAILURE"));assertThat(count("synthetic_records")).isZero();
    }
    @Test void v2LeaseRecoveryRetainsPreparedInputsAndRejectsStaleCompletion(){
        publishV2(v2());var e=startV2("{\"count\":1}","dag-fenced-recovery").execution();var old=claim();var dispatch=runtime.prepare(old);clock.add(31);runtime.reconcile();var fresh=claim();assertThat(fresh.token()).isGreaterThan(old.token());assertThat(runtime.complete(dispatch)).isFalse();worker.dispatch(fresh);drain();assertThat(get(e.id()).status()).isEqualTo(ExecutionState.SUCCEEDED);assertThat(operator.queryForObject("SELECT count(*) FROM execution_step_inputs WHERE execution_id=? AND node_id='start'",Long.class,e.id())).isEqualTo(1);assertThat(count("synthetic_records")).isEqualTo(1);
    }
    @Test void v2EligibleManualRetryRetainsOriginalTypedInputAndLogicalKeys(){
        publishV2(v2());failure("positive",SyntheticCapability.FailureKind.TRANSIENT,3);var e=startV2("{\"count\":1}","dag-manual-retry").execution();drain();clock.add(3);drain();clock.add(3);drain();var failed=get(e.id());assertThat(failed.retryEligible()).isTrue();denied(422,()->executions.retry(owner.id(),owner.family(),org,e.id(),failed.revision(),"Retry exact input","dag-retry-ack"));
        var retry=executions.retry(owner.id(),owner.family(),org,e.id(),failed.revision(),"Retry exact input","dag-retry-new",true).execution();drain();assertThat(get(retry.id()).status()).isEqualTo(ExecutionState.SUCCEEDED);assertThat(get(retry.id()).retryOfExecutionId()).isEqualTo(e.id());assertThat(store.get(org,retry.id(),false).input()).isEqualTo("{\"count\":1}");assertThat(store.steps(org,retry.id()).stream().map(ExecutionStore.Step::operation).toList()).isEqualTo(store.steps(org,e.id()).stream().map(ExecutionStore.Step::operation).toList());assertThat(count("synthetic_records")).isEqualTo(1);
    }
    @Test void v2NestedBranchesSkipOnlyUnselectedArmsAndKeepSharedMergeReachable(){
        var graph=(tools.jackson.databind.node.ObjectNode)graphs.parse(v2());var nodes=(tools.jackson.databind.node.ArrayNode)graph.path("nodes");var edges=(tools.jackson.databind.node.ArrayNode)graph.path("edges");
        nodes.add(graphs.parse("{\"id\":\"nested\",\"type\":\"condition\",\"typeVersion\":1,\"config\":{\"operator\":\"EQ\",\"compare\":1},\"inputs\":{\"value\":{\"from\":\"copy\",\"path\":\"count\"}}}"));
        for(String id:List.of("leftResult","rightResult"))nodes.add(graphs.parse("{\"id\":\""+id+"\",\"type\":\"transform\",\"typeVersion\":1,\"config\":{},\"inputs\":{\"message\":{\"from\":\"positive\",\"path\":\"message\"}}}"));
        for(var edge:edges)if(edge.path("source").asString().equals("positive"))((tools.jackson.databind.node.ObjectNode)edge).put("target","nested");
        edges.add(graphs.parse("{\"source\":\"nested\",\"target\":\"leftResult\",\"branch\":\"true\"}"));edges.add(graphs.parse("{\"source\":\"nested\",\"target\":\"rightResult\",\"branch\":\"false\"}"));for(String id:List.of("leftResult","rightResult"))edges.add(graphs.parse("{\"source\":\""+id+"\",\"target\":\"finish\"}"));
        publishV2(graph.toString());for(int count:new int[]{1,0}){var e=startV2("{\"count\":"+count+"}","dag-nested-"+count).execution();drain();assertThat(get(e.id()).status()).isEqualTo(ExecutionState.SUCCEEDED);var steps=executions.steps(owner.id(),owner.family(),org,e.id());assertThat(steps.stream().filter(s->s.nodeId().equals("finish")).findFirst().orElseThrow().status()).isEqualTo("SUCCEEDED");if(count==1){assertThat(steps.stream().filter(s->s.nodeId().equals("leftResult")).findFirst().orElseThrow().output().toString()).contains("Work exists");assertThat(steps.stream().filter(s->Set.of("empty","rightResult").contains(s.nodeId())).toList()).allMatch(s->s.status().equals("SKIPPED"));}else assertThat(steps.stream().filter(s->Set.of("positive","nested","leftResult","rightResult").contains(s.nodeId())).toList()).hasSize(4).allMatch(s->s.status().equals("SKIPPED")&&s.attemptCount()==0);}
    }
    @Test void v2PreparesActualRecordArgumentsBeforeDispatchAndReusesThemAfterCommittedInterruption(){
        publishV2(v2());var e=startV2("{\"count\":1}","dag-prepared-operation").execution();
        while(store.get(org,e.id(),false).position()<3)worker.dispatch(claim());
        var c=claim();var dispatch=runtime.prepare(c);assertThat(dispatch.node().id()).isEqualTo("positive");
        var prepared=operator.queryForMap("SELECT i.id,b.arguments_json,b.argument_hash,i.blueprint_hash FROM execution_operation_inputs i JOIN execution_operation_bindings b USING(organization_id,logical_operation_id) WHERE i.execution_id=?",e.id());
        assertThat(prepared.get("arguments_json")).isEqualTo("{\"message\":\"Work exists\"}");assertThat(prepared.get("argument_hash")).isNotEqualTo(prepared.get("blueprint_hash"));assertThat(count("synthetic_records")).isZero();
        assertThat(runtime.complete(dispatch)).isTrue();clock.add(31);runtime.reconcile();drain();assertThat(get(e.id()).status()).isEqualTo(ExecutionState.SUCCEEDED);assertThat(count("synthetic_records")).isEqualTo(1);assertThat(count("execution_operation_bindings")).isEqualTo(1);assertThat(operator.queryForObject("SELECT id FROM execution_operation_inputs WHERE execution_id=?",UUID.class,e.id())).isEqualTo(prepared.get("id"));
    }
    UUID supportConnection,teamConnection;
    void capabilityConnections(){business.demo(owner.id(),owner.family(),org,true);supportConnection=connections.create(owner.id(),owner.family(),org,"Support read scope","","SUPPORT",graphs.parse("{}"),null).connection().id();teamConnection=connections.create(owner.id(),owner.family(),org,"Team read scope","","TEAMS",graphs.parse("{}"),null).connection().id();}
    String capabilityGraph(boolean approval){
        String graph="""
          {"schemaVersion":2,"nodes":[
           {"id":"finish","type":"end","typeVersion":1,"config":{},"inputs":{}},
           {"id":"resolve","type":"capabilityAction","typeVersion":1,"config":{"integrationId":"%s","revision":0,"operation":"teams.resolve"},"inputs":{"tickets":{"from":"read","path":"tickets"}}},
           {"id":"record","type":"syntheticAction","typeVersion":1,"config":{"operation":"record","message":"Prepared actual ticket plan"},"inputs":{}},
           {"id":"read","type":"capabilityAction","typeVersion":1,"config":{"integrationId":"%s","revision":0,"operation":"support.list"},"inputs":{"limit":{"literal":50},"priorities":{"literal":["HIGH","URGENT"]},"statuses":{"literal":["OPEN","IN_PROGRESS"]}}},
           {"id":"start","type":"manualTrigger","typeVersion":1,"config":{"inputFields":{}},"inputs":{}}
          ],"edges":[{"source":"resolve","target":"record"},{"source":"start","target":"read"},{"source":"record","target":"finish"},{"source":"read","target":"resolve"}]}
          """.formatted(teamConnection,supportConnection);
        if(!approval)return graph;var root=(tools.jackson.databind.node.ObjectNode)graphs.parse(graph);((tools.jackson.databind.node.ArrayNode)root.path("nodes")).add(graphs.parse("{\"id\":\"review\",\"type\":\"approval\",\"typeVersion\":1,\"config\":{\"prompt\":\"Review exact ticket snapshots\"},\"inputs\":{}}"));for(var edge:root.path("edges"))if(edge.path("source").asString().equals("read"))((tools.jackson.databind.node.ObjectNode)edge).put("target","review");((tools.jackson.databind.node.ArrayNode)root.path("edges")).add(graphs.parse("{\"source\":\"review\",\"target\":\"resolve\"}"));return root.toString();
    }
    CapabilityDispatch.Work capabilityWork(ExecutionStore.Claim claim){return new CapabilityDispatch.Work(org,claim.execution(),claim.owner(),claim.token(),Set.of(owner.id()));}
    @Test void capabilityReadsExecuteInEdgeOrderAndPersistExactPlansReceiptsAndResults(){
        capabilityConnections();publishV2(capabilityGraph(false));var e=startV2("{}","capability-read-plan").execution();drain();assertThat(get(e.id()).status()).isEqualTo(ExecutionState.SUCCEEDED);
        var steps=executions.steps(owner.id(),owner.family(),org,e.id());assertThat(steps).extracting(Step::nodeId).containsExactly("start","read","resolve","record","finish");assertThat(steps).allMatch(s->s.status().equals("SUCCEEDED"));
        var plan=steps.stream().filter(s->s.nodeId().equals("resolve")).findFirst().orElseThrow().output().toString();assertThat(plan).contains("\"expectedRevision\":0","\"currentTeamId\":null","\"targetTeamId\"");assertThat(count("integration_operation_receipts")).isEqualTo(2);assertThat(count("execution_operation_bindings")).isEqualTo(3);assertThat(operator.queryForObject("SELECT count(*) FROM support_tickets WHERE assigned_team_id IS NOT NULL",Long.class)).isZero();
    }
    @Test void capabilityExplicitReadApprovalUsesTheSameExactPreparedOperationAndDistinctHuman(){
        capabilityConnections();var decider=member("APPROVER");publishV2(capabilityGraph(true));var e=startV2("{}","capability-approved-read").execution();drain();assertThat(get(e.id()).status()).isEqualTo(ExecutionState.WAITING_FOR_APPROVAL);assertThat(count("integration_operation_receipts")).isEqualTo(1);
        var request=approvalCommands.get(owner.id(),owner.family(),org,get(e.id()).approvalId());assertThat(request.capability()).isEqualTo("teams");assertThat(request.operation()).isEqualTo("resolve");assertThat(request.classification()).isEqualTo("READ");assertThat(request.intentSchemaVersion()).isEqualTo(2);assertThat(request.connection().id()).isEqualTo(teamConnection);assertThat(request.arguments().path("tickets").size()).isEqualTo(2);
        denied(403,()->approvalCommands.decide(owner.id(),owner.family(),org,request.id(),0,"APPROVE","Cannot self approve"));approvalCommands.decide(decider.id(),decider.family(),org,request.id(),0,"APPROVE","Review exact read scope");drain();assertThat(get(e.id()).status()).isEqualTo(ExecutionState.SUCCEEDED);assertThat(count("integration_operation_receipts")).isEqualTo(2);
    }
    @Test void capabilityNonCanonicalConnectionIdentityIsRejectedBeforePublicationAndWork(){
        capabilityConnections();String invalid=capabilityGraph(false).replace(supportConnection.toString(),"AAAAAAAA-AAAA-4AAA-8AAA-AAAAAAAAAAAA");
        assertThatThrownBy(()->publishV2(invalid)).isInstanceOfSatisfying(com.bridgeops.workflows.application.InvalidDefinition.class,e->assertThat(e.validation().errors()).extracting(com.bridgeops.workflows.domain.GraphDefinition.Error::code).contains("CONNECTION_REFERENCE"));assertThat(count("workflow_executions")).isZero();assertThat(count("execution_work_items")).isZero();assertThat(count("integration_operation_receipts")).isZero();
    }
    @Test void capabilityStaleTicketSnapshotFailsPermanentlyAndRetainsTheCompletedRead(){
        capabilityConnections();publishV2(capabilityGraph(false));var e=startV2("{}","capability-stale-snapshot").execution();worker.dispatch(claim());worker.dispatch(claim());operator.update("UPDATE support_tickets SET revision=revision+1 WHERE priority IN ('HIGH','URGENT') AND status='OPEN'");drain();assertThat(get(e.id()).status()).isEqualTo(ExecutionState.FAILED);assertThat(get(e.id()).errorCode()).isEqualTo("STALE_TICKET_PLAN");assertThat(get(e.id()).retryEligible()).isFalse();
        var steps=executions.steps(owner.id(),owner.family(),org,e.id());assertThat(steps.stream().filter(s->s.nodeId().equals("read")).findFirst().orElseThrow().status()).isEqualTo("SUCCEEDED");assertThat(steps.stream().filter(s->s.nodeId().equals("resolve")).findFirst().orElseThrow().status()).isEqualTo("FAILED");assertThat(count("integration_operation_receipts")).isEqualTo(1);assertThat(count("synthetic_records")).isZero();
    }
    @Test void capabilityConnectionChangesCannotReuseAnApprovedOldRevision(){
        capabilityConnections();var decider=member("APPROVER");publishV2(capabilityGraph(true));var e=startV2("{}","capability-stale-connection").execution();drain();var r=approvalCommands.get(owner.id(),owner.family(),org,get(e.id()).approvalId());approvalCommands.decide(decider.id(),decider.family(),org,r.id(),0,"APPROVE","Exact revision");connections.update(owner.id(),owner.family(),org,teamConnection,0,"Team changed","",true,graphs.parse("{}"),null,false);drain();assertThat(get(e.id()).status()).isEqualTo(ExecutionState.FAILED);assertThat(get(e.id()).errorCode()).isEqualTo("STALE_CONNECTION");assertThat(count("integration_operation_receipts")).isEqualTo(1);assertThat(count("synthetic_records")).isZero();assertThat(claim()).isNull();
    }
    @Test void capabilityDisabledConnectionAndInvalidArgumentsNeverDispatchAnOperation(){
        capabilityConnections();publishV2(capabilityGraph(false));var e=startV2("{}","capability-disabled").execution();connections.update(owner.id(),owner.family(),org,supportConnection,0,"Disabled support","",false,graphs.parse("{}"),null,false);drain();assertThat(get(e.id()).errorCode()).isEqualTo("INTEGRATION_DISABLED");assertThat(count("integration_operation_receipts")).isZero();assertThat(executions.steps(owner.id(),owner.family(),org,e.id()).stream().filter(s->s.nodeId().equals("read")).findFirst().orElseThrow().status()).isEqualTo("FAILED");
        supportConnection=connections.create(owner.id(),owner.family(),org,"New read scope","","SUPPORT",graphs.parse("{}"),null).connection().id();publishV2(capabilityGraph(false).replace("[\"OPEN\",\"IN_PROGRESS\"]","[\"INVALID\"]"));var bad=startV2("{}","capability-bad-input").execution();drain();assertThat(get(bad.id()).errorCode()).isEqualTo("INVALID_CAPABILITY_INPUT");assertThat(count("integration_operation_receipts")).isZero();
    }
    @Test void capabilityGatewayRejectsForgedLeaseNodeActorSetAndCancelledWork(){
        capabilityConnections();publishV2(capabilityGraph(false));var e=startV2("{}","capability-forged-work").execution();worker.dispatch(claim());var claim=claim();var dispatch=runtime.prepare(claim);assertThat(dispatch.node().id()).isEqualTo("read");var tx=new org.springframework.transaction.support.TransactionTemplate(transactions);var work=capabilityWork(claim);
        denied(409,()->tx.execute(s->capabilityGateway.perform(new CapabilityDispatch.Work(org,e.id(),claim.owner(),claim.token()+1,work.actors()),"read")));denied(409,()->tx.execute(s->capabilityGateway.perform(work,"resolve")));denied(409,()->tx.execute(s->capabilityGateway.perform(new CapabilityDispatch.Work(org,e.id(),claim.owner(),claim.token(),Set.of(UUID.randomUUID())),"read")));
        executions.cancel(owner.id(),owner.family(),org,e.id(),get(e.id()).revision(),"Stop before read");denied(409,()->tx.execute(s->capabilityGateway.perform(work,"read")));assertThat(runtime.complete(dispatch)).isFalse();assertThat(count("integration_operation_receipts")).isZero();
    }
    @Test void capabilityCommittedReceiptIsReusedBeforeNormalStepCompletion(){
        capabilityConnections();publishV2(capabilityGraph(false));var e=startV2("{}","capability-receipt-recovery").execution();worker.dispatch(claim());var claim=claim();var dispatch=runtime.prepare(claim);var tx=new org.springframework.transaction.support.TransactionTemplate(transactions);var work=capabilityWork(claim);
        var first=tx.execute(s->capabilityGateway.perform(work,"read"));var repeated=tx.execute(s->capabilityGateway.perform(work,"read"));assertThat(first.reused()).isFalse();assertThat(repeated.reused()).isTrue();assertThat(repeated.output()).isEqualTo(first.output());assertThat(count("integration_operation_receipts")).isEqualTo(1);assertThat(runtime.complete(dispatch)).isTrue();runtime.acknowledge(claim);drain();assertThat(get(e.id()).status()).isEqualTo(ExecutionState.SUCCEEDED);assertThat(count("integration_operation_receipts")).isEqualTo(2);assertThat(executions.events(owner.id(),owner.family(),org,e.id(),0,100).items()).extracting(Event::code).contains("RECEIPT_REUSED");
    }
    @Test void capabilityReadReceiptAndStepResultRollbackTogetherWhenTimelineWriteFails(){
        capabilityConnections();publishV2(capabilityGraph(false));var e=startV2("{}","capability-atomic-result").execution();worker.dispatch(claim());
        operator.execute("CREATE FUNCTION reject_capability_result() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.node_id='read' AND NEW.code='STEP_SUCCEEDED' THEN RAISE EXCEPTION 'Controlled timeline failure'; END IF; RETURN NEW; END $$");operator.execute("CREATE TRIGGER reject_capability_result BEFORE INSERT ON execution_events FOR EACH ROW EXECUTE FUNCTION reject_capability_result()");
        try{worker.dispatch(claim());assertThat(count("integration_operation_receipts")).isZero();assertThat(executions.steps(owner.id(),owner.family(),org,e.id()).stream().filter(s->s.nodeId().equals("read")).findFirst().orElseThrow().output()).isNull();}
        finally{operator.execute("DROP TRIGGER reject_capability_result ON execution_events");operator.execute("DROP FUNCTION reject_capability_result()");}
        clock.add(31);runtime.reconcile();drain();assertThat(get(e.id()).status()).isEqualTo(ExecutionState.SUCCEEDED);assertThat(count("integration_operation_receipts")).isEqualTo(2);
    }
    String assignmentGraph(boolean explicit){
        var graph=(tools.jackson.databind.node.ObjectNode)graphs.parse(capabilityGraph(false));
        for(var node:graph.path("nodes"))if(node.path("id").asString().equals("record")){
            var action=(tools.jackson.databind.node.ObjectNode)node;action.put("type","capabilityAction");
            action.set("config",graphs.parse("{\"integrationId\":\""+supportConnection+"\",\"revision\":0,\"operation\":\"support.assignBatch\"}"));
            action.set("inputs",graphs.parse("{\"assignments\":{\"from\":\"resolve\",\"path\":\"assignments\"}}"));
        }
        if(explicit){((tools.jackson.databind.node.ArrayNode)graph.path("nodes")).add(graphs.parse("{\"id\":\"review\",\"type\":\"approval\",\"typeVersion\":1,\"config\":{\"prompt\":\"Review exact assignments\"},\"inputs\":{}}"));for(var edge:graph.path("edges"))if(edge.path("source").asString().equals("resolve"))((tools.jackson.databind.node.ObjectNode)edge).put("target","review");((tools.jackson.databind.node.ArrayNode)graph.path("edges")).add(graphs.parse("{\"source\":\"review\",\"target\":\"record\"}"));}
        return graph.toString();
    }
    com.bridgeops.approvals.application.ApprovalService.View pending(UUID execution){return approvalCommands.get(owner.id(),owner.family(),org,get(execution).approvalId());}
    void approve(Actor decider,UUID execution){var r=pending(execution);approvalCommands.decide(decider.id(),decider.family(),org,r.id(),r.revision(),"APPROVE","Exact immutable business plan");}
    long assignments(){return operator.queryForObject("SELECT count(*) FROM support_tickets WHERE assigned_team_id IS NOT NULL",Long.class);}
    long receipts(String operation){return operator.queryForObject("SELECT count(*) FROM integration_operation_receipts WHERE operation=?",Long.class,operation);}
    @Test void businessAssignmentsRequireDistinctHumanEvenWithoutGraphGateOrOptionalPolicy(){
        capabilityConnections();var decider=member("APPROVER");publishV2(assignmentGraph(false));var e=startV2("{}","business-mandatory-approval").execution();drain();
        assertThat(get(e.id()).status()).isEqualTo(ExecutionState.WAITING_FOR_APPROVAL);assertThat(assignments()).isZero();var r=pending(e.id());assertThat(r.capability()).isEqualTo("support");assertThat(r.operation()).isEqualTo("assignBatch");assertThat(r.classification()).isEqualTo("WRITE");assertThat(r.arguments().path("assignments").size()).isEqualTo(2);
        denied(403,()->approvalCommands.decide(owner.id(),owner.family(),org,r.id(),0,"APPROVE","Self approval"));approve(decider,e.id());drain();
        assertThat(get(e.id()).status()).isEqualTo(ExecutionState.SUCCEEDED);assertThat(assignments()).isEqualTo(2);assertThat(receipts("support.assignBatch")).isEqualTo(2);assertThat(count("integration_batch_items")).isEqualTo(2);
        assertThat(operator.queryForObject("SELECT count(*) FROM organization_audit_events WHERE action='TICKET_ASSIGNED'",Long.class)).isEqualTo(2);assertThat(operator.queryForList("SELECT revision FROM support_tickets WHERE assigned_team_id IS NOT NULL",Long.class)).containsOnly(1L);
        assertThat(((tools.jackson.databind.JsonNode)executions.steps(owner.id(),owner.family(),org,e.id()).stream().filter(s->s.nodeId().equals("record")).findFirst().orElseThrow().output()).path("applied").asInt()).isEqualTo(2);
    }
    @Test void businessExplicitApprovalRejectsStalePlanAtDecisionButStillAllowsRejection(){
        capabilityConnections();var decider=member("APPROVER");publishV2(assignmentGraph(true));var e=startV2("{}","business-stale-decision").execution();drain();var r=pending(e.id());UUID ticket=UUID.fromString(r.arguments().path("assignments").get(0).path("ticketId").asString());operator.update("UPDATE support_tickets SET revision=revision+1 WHERE id=?",ticket);
        denied(409,()->approve(decider,e.id()));assertThat(count("approval_decisions")).isZero();assertThat(assignments()).isZero();approvalCommands.decide(decider.id(),decider.family(),org,r.id(),0,"REJECT","Plan is stale");drain();assertThat(get(e.id()).errorCode()).isEqualTo("STALE_TICKET_PLAN");assertThat(assignments()).isZero();
    }
    @Test void businessApprovedPlanIsRevalidatedOnResumeWithoutReplanning(){
        capabilityConnections();var decider=member("APPROVER");publishV2(assignmentGraph(true));var e=startV2("{}","business-stale-resume").execution();drain();var before=pending(e.id());approve(decider,e.id());operator.update("UPDATE support_tickets SET revision=revision+1 WHERE priority='HIGH'");drain();assertThat(get(e.id()).errorCode()).isEqualTo("STALE_TICKET_PLAN");assertThat(assignments()).isZero();assertThat(pendingIntent(before.id())).isEqualTo(before.intentHash());assertThat(receipts("support.assignBatch")).isZero();
    }
    String pendingIntent(UUID request){return operator.queryForObject("SELECT intent_hash FROM approval_requests WHERE id=?",String.class,request);}
    @Test void businessPartialBatchRetainsFirstEffectAndStopsAtLaterRevisionConflict(){
        capabilityConnections();var decider=member("APPROVER");publishV2(assignmentGraph(false));var e=startV2("{}","business-partial-conflict").execution();drain();var r=pending(e.id());approve(decider,e.id());runtime.reconcile();var c=claim();var dispatch=runtime.prepare(c);assertThat(dispatch.node().id()).isEqualTo("record");
        UUID second=UUID.fromString(r.arguments().path("assignments").get(1).path("ticketId").asString());operator.update("UPDATE support_tickets SET revision=revision+1 WHERE id=?",second);assertThat(runtime.complete(dispatch)).isFalse();
        assertThat(get(e.id()).status()).isEqualTo(ExecutionState.FAILED);assertThat(get(e.id()).errorCode()).isEqualTo("STALE_TICKET_PLAN");assertThat(get(e.id()).retryEligible()).isFalse();assertThat(assignments()).isEqualTo(1);assertThat(receipts("support.assignBatch")).isEqualTo(1);assertThat(count("integration_batch_items")).isEqualTo(2);
        var output=(tools.jackson.databind.JsonNode)executions.steps(owner.id(),owner.family(),org,e.id()).stream().filter(s->s.nodeId().equals("record")).findFirst().orElseThrow().output();assertThat(output.path("applied").asInt()).isEqualTo(1);assertThat(output.path("items").get(0).path("status").asString()).isEqualTo("APPLIED");assertThat(output.path("items").get(1).path("status").asString()).isEqualTo("CONFLICT");denied(409,()->executions.retry(owner.id(),owner.family(),org,e.id(),get(e.id()).revision(),"Do not replay partial batch","business-partial-retry",true));drain();assertThat(assignments()).isEqualTo(1);
    }
    @Test void businessCommittedReceiptsReconcileWithoutRepeatingAssignments(){
        capabilityConnections();var decider=member("APPROVER");publishV2(assignmentGraph(false));var e=startV2("{}","business-receipt-recovery").execution();drain();approve(decider,e.id());runtime.reconcile();var c=claim();var dispatch=runtime.prepare(c);var tx=new org.springframework.transaction.support.TransactionTemplate(transactions);var work=new CapabilityDispatch.Work(org,e.id(),c.owner(),c.token(),Set.of(owner.id(),decider.id()));
        assertThat(tx.execute(s->capabilityGateway.perform(work,"record")).reused()).isFalse();assertThat(assignments()).isEqualTo(2);assertThat(tx.execute(s->capabilityGateway.perform(work,"record")).reused()).isTrue();assertThat(runtime.complete(dispatch)).isTrue();runtime.acknowledge(c);drain();assertThat(get(e.id()).status()).isEqualTo(ExecutionState.SUCCEEDED);assertThat(receipts("support.assignBatch")).isEqualTo(2);assertThat(operator.queryForList("SELECT revision FROM support_tickets WHERE assigned_team_id IS NOT NULL",Long.class)).containsOnly(1L);assertThat(operator.queryForObject("SELECT count(*) FROM organization_audit_events WHERE action='TICKET_ASSIGNED'",Long.class)).isEqualTo(2);
    }
    @Test void businessAuditFailureRollsBackEffectsReceiptsAttemptsAndResults(){
        capabilityConnections();var decider=member("APPROVER");publishV2(assignmentGraph(false));var e=startV2("{}","business-audit-rollback").execution();drain();approve(decider,e.id());runtime.reconcile();var c=claim();var dispatch=runtime.prepare(c);
        operator.execute("CREATE FUNCTION reject_business_audit() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.action='TICKET_ASSIGNED' THEN RAISE EXCEPTION 'Controlled business audit failure'; END IF; RETURN NEW; END $$");operator.execute("CREATE TRIGGER reject_business_audit BEFORE INSERT ON organization_audit_events FOR EACH ROW EXECUTE FUNCTION reject_business_audit()");
        try{assertThatThrownBy(()->runtime.complete(dispatch)).isInstanceOf(org.springframework.dao.DataAccessException.class);assertThat(assignments()).isZero();assertThat(receipts("support.assignBatch")).isZero();assertThat(count("integration_batch_items")).isZero();assertThat(executions.steps(owner.id(),owner.family(),org,e.id()).stream().filter(s->s.nodeId().equals("record")).findFirst().orElseThrow().output()).isNull();}finally{operator.execute("DROP TRIGGER reject_business_audit ON organization_audit_events");operator.execute("DROP FUNCTION reject_business_audit()");}
        clock.add(31);runtime.reconcile();drain();assertThat(get(e.id()).status()).isEqualTo(ExecutionState.SUCCEEDED);assertThat(assignments()).isEqualTo(2);assertThat(receipts("support.assignBatch")).isEqualTo(2);
    }
    @Test void businessCancellationAndPolicyRevocationPreventApprovedEffects(){
        capabilityConnections();var decider=member("APPROVER");publishV2(assignmentGraph(false));var e=startV2("{}","business-cancelled").execution();drain();approve(decider,e.id());executions.cancel(owner.id(),owner.family(),org,e.id(),get(e.id()).revision(),"Cancel protected writes");drain();assertThat(get(e.id()).status()).isEqualTo(ExecutionState.CANCELLED);assertThat(assignments()).isZero();
        var second=startV2("{}","business-policy-revoked").execution();drain();approve(decider,second.id());runtime.reconcile();var c=claim();var dispatch=runtime.prepare(c);approvalCommands.updatePolicy(owner.id(),owner.family(),org,0,true,3600);assertThat(runtime.complete(dispatch)).isFalse();assertThat(get(second.id()).errorCode()).isEqualTo("POLICY_CHANGED");assertThat(assignments()).isZero();assertThat(receipts("support.assignBatch")).isZero();
    }
    String singleBusinessGraph(String type,String operation,UUID connection,String inputs){return """
      {"schemaVersion":2,"nodes":[{"id":"start","type":"manualTrigger","typeVersion":1,"config":{"inputFields":{}},"inputs":{}},{"id":"effect","type":"%s","typeVersion":1,"config":{"integrationId":"%s","revision":0%s},"inputs":%s},{"id":"finish","type":"end","typeVersion":1,"config":{},"inputs":{}}],"edges":[{"source":"start","target":"effect"},{"source":"effect","target":"finish"}]}
      """.formatted(type,connection,type.equals("notification")?"":",\"operation\":\""+operation+"\"",inputs);}
    @Test void businessNoteUsesApprovedExactRevisionAndReusesCommittedReceipt(){
        capabilityConnections();UUID ticket=operator.queryForList("SELECT id FROM support_tickets ORDER BY id LIMIT 1",UUID.class).getFirst();var decider=member("APPROVER");publishV2(singleBusinessGraph("capabilityAction","support.addNote",supportConnection,"{\"ticketId\":{\"literal\":\""+ticket+"\"},\"expectedRevision\":{\"literal\":0},\"note\":{\"literal\":\"Reviewed internal ticket\"}}"));var e=startV2("{}","business-note").execution();drain();assertThat(count("support_ticket_notes")).isZero();approve(decider,e.id());runtime.reconcile();var c=claim();var dispatch=runtime.prepare(c);var tx=new org.springframework.transaction.support.TransactionTemplate(transactions);var work=new CapabilityDispatch.Work(org,e.id(),c.owner(),c.token(),Set.of(owner.id(),decider.id()));tx.execute(s->capabilityGateway.perform(work,"effect"));assertThat(tx.execute(s->capabilityGateway.perform(work,"effect")).reused()).isTrue();assertThat(runtime.complete(dispatch)).isTrue();runtime.acknowledge(c);drain();assertThat(get(e.id()).status()).isEqualTo(ExecutionState.SUCCEEDED);assertThat(count("support_ticket_notes")).isEqualTo(1);assertThat(receipts("support.addNote")).isEqualTo(1);assertThat(operator.queryForObject("SELECT revision FROM support_tickets WHERE id=?",Long.class,ticket)).isEqualTo(1);assertThat(operator.queryForObject("SELECT count(*) FROM organization_audit_events WHERE action='TICKET_NOTE_ADDED'",Long.class)).isEqualTo(1);
    }
    @Test void businessNotificationPersistsInternalDeliveryAndReceiptWithoutHumanOrSmtp(){
        UUID connection=connections.create(owner.id(),owner.family(),org,"Internal delivery","","NOTIFICATIONS",graphs.parse("{}"),null).connection().id();publishV2(singleBusinessGraph("notification",null,connection,"{\"message\":{\"literal\":\"Internal completion notice\"}}"));var e=startV2("{}","business-internal-notification").execution();worker.dispatch(claim());var c=claim();var dispatch=runtime.prepare(c);var tx=new org.springframework.transaction.support.TransactionTemplate(transactions);tx.execute(s->capabilityGateway.perform(capabilityWork(c),"effect"));assertThat(tx.execute(s->capabilityGateway.perform(capabilityWork(c),"effect")).reused()).isTrue();assertThat(runtime.complete(dispatch)).isTrue();runtime.acknowledge(c);drain();assertThat(get(e.id()).status()).isEqualTo(ExecutionState.SUCCEEDED);assertThat(count("internal_notifications")).isEqualTo(1);assertThat(count("internal_notification_attempts")).isEqualTo(1);assertThat(receipts("notifications.create")).isEqualTo(1);assertThat(count("approval_requests")).isZero();assertThat(operator.queryForObject("SELECT provenance FROM internal_notifications",String.class)).isEqualTo("LIVE_EXECUTION");assertThat(operator.queryForObject("SELECT outcome FROM internal_notification_attempts",String.class)).isEqualTo("DELIVERED_INTERNAL");
    }
    @Test void changedArgumentsForAnExistingOperationReceiptFailForReconciliation(){
        var e=start("receipt-argument-key").execution();var step=store.steps(org,e.id()).stream().filter(s1->s1.node().equals("first")).findFirst().orElseThrow();
        operator.update("INSERT INTO synthetic_records(organization_id,logical_operation_id,message,recorded_at) VALUES(?,?,?,?)",org,step.operation(),"Controlled prior effect",java.sql.Timestamp.from(clock.instant()));
        operator.update("INSERT INTO execution_operation_receipts(organization_id,logical_operation_id,capability,item_id,argument_hash,result_json,committed_at) VALUES(?,?,'synthetic.record',?,?,'{}',?)",org,step.operation(),step.node(),"a".repeat(64),java.sql.Timestamp.from(clock.instant()));
        drain();assertThat(get(e.id()).errorCode()).isEqualTo("RECONCILIATION_REQUIRED");assertThat(get(e.id()).retryEligible()).isFalse();assertThat(count("synthetic_records")).isEqualTo(1);assertThat(count("execution_operation_receipts")).isEqualTo(1);
    }

}
