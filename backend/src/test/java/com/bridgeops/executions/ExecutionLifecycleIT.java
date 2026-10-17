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
        workflows.save(owner.id(),owner.family(),org,workflow,1,"Approval","",graphs.parse(GRAPH.replace("\"type\":\"syntheticAction\",\"typeVersion\":1,\"config\":{\"operation\":\"record\",\"message\":\"First record\"}","\"type\":\"approval\",\"typeVersion\":1,\"config\":{\"prompt\":\"Review\"}")));
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
        var restarted=new ExecutionRuntime(store,transactions,workerAuthority,snapshots,definitions,executions,executionAudit,clock,randomness);
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
    @Test void changedArgumentsForAnExistingOperationReceiptFailForReconciliation(){
        var e=start("receipt-argument-key").execution();var step=store.steps(org,e.id()).stream().filter(s1->s1.node().equals("first")).findFirst().orElseThrow();
        operator.update("INSERT INTO synthetic_records(organization_id,logical_operation_id,message,recorded_at) VALUES(?,?,?,?)",org,step.operation(),"Controlled prior effect",java.sql.Timestamp.from(clock.instant()));
        operator.update("INSERT INTO execution_operation_receipts(organization_id,logical_operation_id,capability,item_id,argument_hash,result_json,committed_at) VALUES(?,?,'synthetic.record',?,?,'{}',?)",org,step.operation(),step.node(),"a".repeat(64),java.sql.Timestamp.from(clock.instant()));
        drain();assertThat(get(e.id()).errorCode()).isEqualTo("RECONCILIATION_REQUIRED");assertThat(get(e.id()).retryEligible()).isFalse();assertThat(count("synthetic_records")).isEqualTo(1);assertThat(count("execution_operation_receipts")).isEqualTo(1);
    }

}
