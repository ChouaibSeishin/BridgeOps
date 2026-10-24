package com.bridgeops.executions;

import com.bridgeops.approvals.application.OperationApprovalIntent;
import com.bridgeops.executions.domain.ExecutionPlan;
import com.bridgeops.executions.persistence.*;
import com.bridgeops.organizations.persistence.OrganizationStore;
import com.bridgeops.shared.web.DomainFailure;
import com.bridgeops.workflows.domain.GraphDefinition;
import com.bridgeops.workflows.persistence.WorkflowStore;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
class OperationInputMigrationIT {
    @Container static final PostgreSQLContainer postgres=new PostgreSQLContainer(DockerImageName.parse("postgres@sha256:77f585114c32fbca283dc835b0596f4e52b51b4c6662d7810b2f4084f60a1873").asCompatibleSubstituteFor("postgres")).withInitScript("organization-runtime.sql");
    String database(){String name="operation_inputs_"+UUID.randomUUID().toString().replace("-","");new JdbcTemplate(source(postgres.getJdbcUrl(),false)).execute("CREATE DATABASE "+name);return postgres.getJdbcUrl().replace("/"+postgres.getDatabaseName(),"/"+name);}
    DriverManagerDataSource source(String url,boolean runtime){return new DriverManagerDataSource(url,runtime?"bridgeops_app":postgres.getUsername(),runtime?"synthetic-runtime-fixture":postgres.getPassword());}
    Flyway migrate(String url,String target){return Flyway.configure().dataSource(source(url,false)).locations("classpath:db/migration").target(target).load();}
    @Test void durableWebhookMigrationIsAdditiveFromPopulatedV11AndRestrictsHistoryWrites(){
        String fresh=database();assertThat(migrate(fresh,"23").migrate().migrationsExecuted).isEqualTo(23);
        var empty=new JdbcTemplate(source(fresh,false));for(String table:List.of("webhook_delivery_receipts","webhook_ingress_quotas","webhook_ingress_leases","workflow_executions","integration_secrets"))assertThat(empty.queryForObject("SELECT count(*) FROM "+table,Long.class)).isZero();
        assertThat(empty.queryForObject("SELECT has_table_privilege('bridgeops_app','webhook_delivery_receipts','INSERT') AND NOT has_table_privilege('bridgeops_app','webhook_delivery_receipts','UPDATE,DELETE,TRUNCATE') AND NOT has_table_privilege('bridgeops_app','webhook_ingress_leases','UPDATE,TRUNCATE')",Boolean.class)).isTrue();
        String url=database();migrate(url,"11").migrate();var db=new JdbcTemplate(source(url,false));new GraphDataMigrationIT().seedV11(db);var before=new TreeMap<String,List<Map<String,Object>>>();var columns=new TreeMap<String,String>();
        for(String table:db.queryForList("SELECT tablename FROM pg_tables WHERE schemaname='public' AND tablename<>'flyway_schema_history'",String.class)){String projection=db.queryForObject("SELECT string_agg(quote_ident(column_name),',' ORDER BY ordinal_position) FROM information_schema.columns WHERE table_schema='public' AND table_name=?",String.class,table);columns.put(table,projection);before.put(table,db.queryForList("SELECT "+projection+" FROM "+table));}
        assertThat(migrate(url,"23").migrate().migrationsExecuted).isEqualTo(12);before.forEach((table,rows)->assertThat(db.queryForList("SELECT "+columns.get(table)+" FROM "+table)).as(table).containsExactlyInAnyOrderElementsOf(rows));assertThat(db.queryForObject("SELECT count(*) FROM webhook_delivery_receipts",Long.class)).isZero();
    }
    record Fixture(String url,JdbcTemplate db,JdbcTemplate runtime,TransactionTemplate tx,UUID actor,UUID org,UUID workflow,UUID version,UUID execution,UUID operation,String blueprint){}
    Fixture fixture(){
        String url=database();migrate(url,"17").migrate();var db=new JdbcTemplate(source(url,false));var runtimeSource=source(url,true);var runtime=new JdbcTemplate(runtimeSource);var tx=new TransactionTemplate(new DataSourceTransactionManager(runtimeSource));Instant now=Instant.now();UUID actor=UUID.randomUUID();
        db.update("INSERT INTO users(id,email,normalized_email,password_hash,display_name,account_status,created_at,email_verified_at) VALUES(?,?,?,'synthetic-password-hash','Fixture','ACTIVE',now(),now())",actor,actor+"@example.invalid",actor+"@example.invalid");
        UUID org=new OrganizationStore(db).insert(actor,"Operation fixture","operation-fixture",now).id();var graphs=new GraphDefinition();var graph=graphs.parse(com.bridgeops.workflows.GraphV2DefinitionTest.GRAPH);var canonical=graphs.canonical(graph);var workflows=new WorkflowStore(db);UUID workflow=workflows.create(org,actor,"Prepared operation","",canonical.json(),now);UUID version=workflows.publish(workflows.get(org,workflow,false),actor,canonical.json(),canonical.hash(),2,2,now);UUID execution=UUID.randomUUID(),operation=UUID.randomUUID();
        var executions=new ExecutionStore(db);executions.insertV2(execution,org,workflow,version,canonical.hash(),actor,"MANUAL","{\"count\":1}","prepared-fixture-key","a".repeat(64),null,now,now);String blueprint="b".repeat(64);executions.insertStepV2(org,execution,"positive",0,"syntheticAction",operation,blueprint);
        return new Fixture(url,db,runtime,tx,actor,org,workflow,version,execution,operation,blueprint);
    }
    OperationInputStore.Prepared prepare(Fixture f,String args){return f.tx().execute(s->new OperationInputStore(f.runtime()).prepare(f.org(),f.execution(),"positive",f.operation(),f.blueprint(),"synthetic","record","WRITE",null,new GraphDefinition().parse(args),Instant.now()));}
    @Test void freshSchemaAndPopulatedV11UpgradeKeepEveryHistoricalColumn(){
        String fresh=database();assertThat(migrate(fresh,"17").migrate().migrationsExecuted).isEqualTo(17);var empty=new JdbcTemplate(source(fresh,false));assertThat(empty.queryForObject("SELECT count(*) FROM execution_operation_bindings",Long.class)).isZero();
        String url=database();migrate(url,"11").migrate();var db=new JdbcTemplate(source(url,false));new GraphDataMigrationIT().seedV11(db);var tables=db.queryForList("SELECT tablename FROM pg_tables WHERE schemaname='public' AND tablename<>'flyway_schema_history' ORDER BY tablename",String.class);Map<String,String> projections=new TreeMap<>();Map<String,List<Map<String,Object>>> rows=new TreeMap<>();
        for(String table:tables){String columns=db.queryForObject("SELECT string_agg(quote_ident(column_name),',' ORDER BY ordinal_position) FROM information_schema.columns WHERE table_schema='public' AND table_name=?",String.class,table);projections.put(table,columns);rows.put(table,db.queryForList("SELECT "+columns+" FROM "+table));}
        assertThat(migrate(url,"17").migrate().migrationsExecuted).isEqualTo(6);rows.forEach((table,before)->assertThat(db.queryForList("SELECT "+projections.get(table)+" FROM "+table)).as(table).containsExactlyInAnyOrderElementsOf(before));
        assertThat(db.queryForObject("SELECT count(*) FROM execution_operation_inputs",Long.class)).isZero();
    }
    @Test void demoWorkflowMigrationCreatesNoArtifactsAndPreservesPopulatedV11(){
        String fresh=database();assertThat(migrate(fresh,"20").migrate().migrationsExecuted).isEqualTo(20);var empty=new JdbcTemplate(source(fresh,false));assertThat(empty.queryForObject("SELECT count(*) FROM integration_demo_workflows",Long.class)).isZero();for(String permission:List.of("UPDATE","DELETE","TRUNCATE"))assertThat(empty.queryForObject("SELECT has_table_privilege('bridgeops_app','integration_demo_workflows',?)",Boolean.class,permission)).isFalse();
        String url=database();migrate(url,"11").migrate();var db=new JdbcTemplate(source(url,false));new GraphDataMigrationIT().seedV11(db);var before=new TreeMap<String,List<Map<String,Object>>>();var columns=new TreeMap<String,String>();for(String table:db.queryForList("SELECT tablename FROM pg_tables WHERE schemaname='public' AND tablename<>'flyway_schema_history'",String.class)){String projection=db.queryForObject("SELECT string_agg(quote_ident(column_name),',' ORDER BY ordinal_position) FROM information_schema.columns WHERE table_schema='public' AND table_name=?",String.class,table);columns.put(table,projection);before.put(table,db.queryForList("SELECT "+projection+" FROM "+table));}assertThat(migrate(url,"20").migrate().migrationsExecuted).isEqualTo(9);before.forEach((table,rows)->assertThat(db.queryForList("SELECT "+columns.get(table)+" FROM "+table)).as(table).containsExactlyInAnyOrderElementsOf(rows));assertThat(db.queryForObject("SELECT count(*) FROM integration_demo_workflows",Long.class)).isZero();
    }
    @Test void webhookConfigurationMigrationIsEmptyAndPreservesEveryPopulatedV11Column(){
        String fresh=database();assertThat(migrate(fresh,"21").migrate().migrationsExecuted).isEqualTo(21);var empty=new JdbcTemplate(source(fresh,false));for(String table:List.of("webhook_endpoints","webhook_endpoint_versions","integration_secrets","automation_principals","workflow_executions"))assertThat(empty.queryForObject("SELECT count(*) FROM "+table,Long.class)).isZero();
        String url=database();migrate(url,"11").migrate();var db=new JdbcTemplate(source(url,false));new GraphDataMigrationIT().seedV11(db);var before=new TreeMap<String,List<Map<String,Object>>>();var columns=new TreeMap<String,String>();for(String table:db.queryForList("SELECT tablename FROM pg_tables WHERE schemaname='public' AND tablename<>'flyway_schema_history'",String.class)){String projection=db.queryForObject("SELECT string_agg(quote_ident(column_name),',' ORDER BY ordinal_position) FROM information_schema.columns WHERE table_schema='public' AND table_name=?",String.class,table);columns.put(table,projection);before.put(table,db.queryForList("SELECT "+projection+" FROM "+table));}assertThat(migrate(url,"21").migrate().migrationsExecuted).isEqualTo(10);before.forEach((table,rows)->assertThat(db.queryForList("SELECT "+columns.get(table)+" FROM "+table)).as(table).containsExactlyInAnyOrderElementsOf(rows));assertThat(db.queryForObject("SELECT count(*) FROM webhook_endpoints",Long.class)).isZero();
    }
    @Test void explicitActorMigrationKeepsEveryHistoricalV11ValueAndNarrowPrivileges(){
        String fresh=database();assertThat(migrate(fresh,"22").migrate().migrationsExecuted).isEqualTo(22);var empty=new JdbcTemplate(source(fresh,false));for(String table:List.of("webhook_endpoints","webhook_endpoint_versions","integration_secrets","automation_principals","workflow_executions","approval_requests"))assertThat(empty.queryForObject("SELECT count(*) FROM "+table,Long.class)).isZero();
        for(String table:List.of("workflow_executions","approval_requests"))for(String column:List.of("requester_type","automation_principal_id","principal_created_by","webhook_endpoint_id","webhook_endpoint_revision","endpoint_revision_author"))assertThat(empty.queryForObject("SELECT has_column_privilege('bridgeops_app',?,?,'UPDATE')",Boolean.class,table,column)).isFalse();
        String url=database();migrate(url,"11").migrate();var db=new JdbcTemplate(source(url,false));new GraphDataMigrationIT().seedV11(db);var before=new TreeMap<String,List<Map<String,Object>>>();var columns=new TreeMap<String,String>();for(String table:db.queryForList("SELECT tablename FROM pg_tables WHERE schemaname='public' AND tablename<>'flyway_schema_history'",String.class)){String projection=db.queryForObject("SELECT string_agg(quote_ident(column_name),',' ORDER BY ordinal_position) FROM information_schema.columns WHERE table_schema='public' AND table_name=?",String.class,table);columns.put(table,projection);before.put(table,db.queryForList("SELECT "+projection+" FROM "+table));}assertThat(migrate(url,"22").migrate().migrationsExecuted).isEqualTo(11);before.forEach((table,rows)->assertThat(db.queryForList("SELECT "+columns.get(table)+" FROM "+table)).as(table).containsExactlyInAnyOrderElementsOf(rows));
        var human=new ExecutionStore(db).list(db.queryForObject("SELECT id FROM organizations",UUID.class),0,25,null,null).getFirst();assertThat(human.requester().type()).isEqualTo(com.bridgeops.shared.domain.ActorReference.Type.USER);assertThat(human.requester().id()).isEqualTo(human.actor());assertThat(human.automation()).isNull();assertThat(db.queryForObject("SELECT bool_and(requester_type='USER' AND automation_principal_id IS NULL) FROM approval_requests",Boolean.class)).isTrue();
    }
    @Test void preparationIsStableAndChangedArgumentsFailWithoutLeakingValues(){
        var f=fixture();var first=prepare(f,"{\"message\":\"safe fixture\"}");assertThat(prepare(f,"{\"message\":\"safe fixture\"}")).isEqualTo(first);assertThat(first.argumentHash()).isEqualTo(ExecutionPlan.hash(first.arguments()));assertThat(first.toString()).doesNotContain("safe fixture",first.id().toString());
        assertThatThrownBy(()->prepare(f,"{\"message\":\"private changed value\"}")).isInstanceOfSatisfying(DomainFailure.class,e->{assertThat(e.code()).isEqualTo("OPERATION_INTENT_MISMATCH");assertThat(e.toString()).doesNotContain("private changed value");});assertThat(f.db().queryForObject("SELECT count(*) FROM execution_operation_bindings",Long.class)).isEqualTo(1);
        assertThatThrownBy(()->new OperationInputStore(f.runtime()).prepare(f.org(),f.execution(),"positive",f.operation(),f.blueprint(),"synthetic","record","WRITE",null,new GraphDefinition().parse("{}"),Instant.now())).isInstanceOf(IllegalStateException.class);
    }
    @Test void concurrentPreparationCommitsOneBindingAndOneStablePreparedIdentity()throws Exception{
        var f=fixture();try(var pool=Executors.newFixedThreadPool(2)){var a=pool.submit(()->prepare(f,"{\"message\":\"concurrent\"}"));var b=pool.submit(()->prepare(f,"{\"message\":\"concurrent\"}"));assertThat(a.get(20,TimeUnit.SECONDS)).isEqualTo(b.get(20,TimeUnit.SECONDS));}assertThat(f.db().queryForObject("SELECT count(*) FROM execution_operation_inputs",Long.class)).isEqualTo(1);
    }
    @Test void simultaneousPreparationHandlesEveryUniqueContextIndex()throws Exception{
        var initial=fixture();migrate(initial.url(),"19").migrate();
        try(var pool=Executors.newFixedThreadPool(8)){
            for(int round=0;round<5;round++){
                UUID execution=UUID.randomUUID(),operation=UUID.randomUUID();var original=new ExecutionStore(initial.db()).get(initial.org(),initial.execution(),false);var store=new ExecutionStore(initial.db());Instant now=Instant.now();store.insertV2(execution,initial.org(),initial.workflow(),initial.version(),original.hash(),initial.actor(),"MANUAL","{\"count\":1}","simultaneous-prepared-"+round,"e".repeat(64),null,now,now);store.insertStepV2(initial.org(),execution,"positive",0,"syntheticAction",operation,initial.blueprint());
                var f=new Fixture(initial.url(),initial.db(),initial.runtime(),initial.tx(),initial.actor(),initial.org(),initial.workflow(),initial.version(),execution,operation,initial.blueprint());var ready=new CountDownLatch(8);var start=new CountDownLatch(1);var futures=new ArrayList<Future<OperationInputStore.Prepared>>();
                for(int i=0;i<8;i++)futures.add(pool.submit(()->{ready.countDown();if(!start.await(20,TimeUnit.SECONDS))throw new AssertionError("Concurrent fixture did not start");return prepare(f,"{\"message\":\"simultaneous exact context\"}");}));assertThat(ready.await(20,TimeUnit.SECONDS)).isTrue();start.countDown();var expected=futures.getFirst().get(30,TimeUnit.SECONDS);for(var result:futures)assertThat(result.get(30,TimeUnit.SECONDS)).isEqualTo(expected);
            }
        }
        assertThat(initial.db().queryForObject("SELECT count(*) FROM execution_operation_bindings",Long.class)).isEqualTo(5);assertThat(initial.db().queryForObject("SELECT count(*) FROM execution_operation_inputs",Long.class)).isEqualTo(5);
    }
    @Test void businessReceiptMigrationPreservesPopulatedV11AndRequiresActualStepAndArguments(){
        String fresh=database();assertThat(migrate(fresh,"19").migrate().migrationsExecuted).isEqualTo(19);
        String upgrade=database();migrate(upgrade,"11").migrate();var historical=new JdbcTemplate(source(upgrade,false));new GraphDataMigrationIT().seedV11(historical);var before=new TreeMap<String,List<Map<String,Object>>>();var columns=new TreeMap<String,String>();for(String table:historical.queryForList("SELECT tablename FROM pg_tables WHERE schemaname='public' AND tablename<>'flyway_schema_history'",String.class)){String projection=historical.queryForObject("SELECT string_agg(quote_ident(column_name),',' ORDER BY ordinal_position) FROM information_schema.columns WHERE table_schema='public' AND table_name=?",String.class,table);columns.put(table,projection);before.put(table,historical.queryForList("SELECT "+projection+" FROM "+table));}assertThat(migrate(upgrade,"19").migrate().migrationsExecuted).isEqualTo(8);before.forEach((table,rows)->assertThat(historical.queryForList("SELECT "+columns.get(table)+" FROM "+table)).as(table).containsExactlyInAnyOrderElementsOf(rows));
        var f=fixture();var prepared=prepare(f,"{\"message\":\"receipt binding\"}");migrate(f.url(),"19").migrate();var receipts=new com.bridgeops.integrations.persistence.IntegrationWorkStore(f.runtime());f.tx().executeWithoutResult(s->receipts.commit(f.org(),f.execution(),f.operation(),"synthetic.record","positive",prepared.argumentHash(),"{}",Instant.now()));assertThat(f.db().queryForObject("SELECT count(*) FROM integration_operation_receipts",Long.class)).isEqualTo(1);
        assertThatThrownBy(()->f.tx().executeWithoutResult(s->receipts.commit(f.org(),f.execution(),UUID.randomUUID(),"synthetic.record","other",prepared.argumentHash(),"{}",Instant.now()))).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);assertThatThrownBy(()->f.tx().executeWithoutResult(s->receipts.commit(f.org(),f.execution(),f.operation(),"synthetic.record","other","a".repeat(64),"{}",Instant.now()))).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        for(String table:List.of("integration_operation_receipts","integration_batch_items","support_ticket_notes","internal_notifications","internal_notification_attempts")){assertThat(f.db().queryForObject("SELECT has_table_privilege('bridgeops_app',?,'DELETE')",Boolean.class,table)).isFalse();assertThat(f.db().queryForObject("SELECT has_table_privilege('bridgeops_app',?,'UPDATE')",Boolean.class,table)).isFalse();assertThat(f.db().queryForObject("SELECT has_table_privilege('bridgeops_app',?,'TRUNCATE')",Boolean.class,table)).isFalse();}
    }
    @Test void rollbackLeavesNoPartialBindingOrPreparedIdentity(){
        var f=fixture();assertThatThrownBy(()->f.tx().execute(s->{prepare(f,"{\"message\":\"rollback\"}");throw new IllegalStateException("Controlled interruption");})).isInstanceOf(IllegalStateException.class);for(String table:List.of("execution_operation_bindings","execution_operation_inputs"))assertThat(f.db().queryForObject("SELECT count(*) FROM "+table,Long.class)).isZero();
    }
    @Test void transactionOnAnUnrelatedDataSourceCannotPermitAutocommittedPreparation(){
        var f=fixture();var unrelated=new TransactionTemplate(new DataSourceTransactionManager(source(f.url(),true)));
        assertThatThrownBy(()->unrelated.execute(s->new OperationInputStore(f.runtime()).prepare(f.org(),f.execution(),"positive",f.operation(),f.blueprint(),"synthetic","record","WRITE",null,new GraphDefinition().parse("{}"),Instant.now()))).isInstanceOf(IllegalStateException.class);
        assertThat(f.db().queryForObject("SELECT count(*) FROM execution_operation_bindings",Long.class)).isZero();
    }
    @Test void retryExecutionReusesLogicalBindingButHasItsOwnPreparedIntent(){
        var f=fixture();var first=prepare(f,"{\"message\":\"retained arguments\"}");var executions=new ExecutionStore(f.db());UUID retry=UUID.randomUUID();Instant now=Instant.now();var original=executions.get(f.org(),f.execution(),false);executions.insertV2(retry,f.org(),f.workflow(),f.version(),original.hash(),f.actor(),"RETRY",original.input(),"prepared-retry-key","c".repeat(64),f.execution(),now,now);executions.insertStepV2(f.org(),retry,"positive",0,"syntheticAction",f.operation(),f.blueprint());
        var second=f.tx().execute(s->new OperationInputStore(f.runtime()).prepare(f.org(),retry,"positive",f.operation(),f.blueprint(),"synthetic","record","WRITE",null,new GraphDefinition().parse(first.arguments()),now));assertThat(second.id()).isNotEqualTo(first.id());assertThat(second.argumentHash()).isEqualTo(first.argumentHash());assertThat(f.db().queryForObject("SELECT count(*) FROM execution_operation_bindings",Long.class)).isEqualTo(1);assertThat(f.db().queryForObject("SELECT count(*) FROM execution_operation_inputs",Long.class)).isEqualTo(2);
    }
    @Test void schema2ApprovalBindsPreparedArgumentsAndRejectsForgedContext(){
        var f=fixture();var prepared=prepare(f,"{\"message\":\"Exact protected operation\"}");assertThat(migrate(f.url(),"18").migrate().migrationsExecuted).isEqualTo(1);
        var approvals=new com.bridgeops.approvals.persistence.ApprovalStore(f.runtime());Instant now=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);approvals.ensurePolicy(f.org(),now);var e=new ExecutionStore(f.runtime()).get(f.org(),f.execution(),false);
        var intent=com.bridgeops.approvals.application.OperationApprovalIntent.create(f.org(),f.execution(),"positive","positive",f.version(),e.hash(),com.bridgeops.shared.domain.ActorReference.user(f.actor()),f.operation(),prepared.id(),"synthetic","record","WRITE",null,new GraphDefinition().parse(prepared.arguments()),0,new GraphDefinition().parse(approvals.policy(f.org()).snapshot()),"Review exact prepared operation",now,now.plusSeconds(600),e.deadline());
        var request=approvals.insert(intent,f.blueprint());assertThat(request.generalized()).isTrue();assertThat(request.operationIntent()).isEqualTo(intent);assertThat(request.intentHash()).isEqualTo(intent.hash());
        for(int changed=0;changed<4;changed++){
            var altered=com.bridgeops.approvals.application.OperationApprovalIntent.create(f.org(),f.execution(),"positive","positive",f.version(),e.hash(),com.bridgeops.shared.domain.ActorReference.user(f.actor()),f.operation(),changed==0?UUID.randomUUID():prepared.id(),changed==2?"rest":"synthetic",changed==2?"update":"record","WRITE",changed==2?new OperationApprovalIntent.Connection(UUID.randomUUID(),0,1,"update"):null,new GraphDefinition().parse(changed==1?"{\"message\":\"Changed\"}":prepared.arguments()),0,new GraphDefinition().parse(changed==3?"{\"lifetimeSeconds\":3600,\"required\":true}":approvals.policy(f.org()).snapshot()),"Review exact prepared operation",now,now.plusSeconds(600),e.deadline());
            assertThatThrownBy(()->approvals.insert(altered,f.blueprint())).isInstanceOfSatisfying(org.springframework.dao.DataIntegrityViolationException.class,failure->assertThat(((java.sql.SQLException)failure.getMostSpecificCause()).getSQLState()).isEqualTo("23514"));
        }
        assertThat(f.db().queryForObject("SELECT count(*) FROM approval_requests",Long.class)).isEqualTo(1);
    }
    @Test void schema2ConnectionRevisionAndClassificationAreDurablyBound(){
        var f=fixture();UUID connection=UUID.randomUUID(),logical=UUID.randomUUID();Instant now=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);String definition="{}";
        f.db().update("INSERT INTO integration_connections(id,organization_id,name,integration_type,configuration_json,created_by,created_at,updated_at) VALUES(?,?,'Fixture REST','REST','{}',?,now(),now())",connection,f.org(),f.actor());
        f.db().update("INSERT INTO integration_connection_versions VALUES(?,?,0,?,?,now())",f.org(),connection,definition,ExecutionPlan.hash(definition));
        new ExecutionStore(f.db()).insertStepV2(f.org(),f.execution(),"external",1,"configuredHttp",logical,"c".repeat(64));
        var prepared=f.tx().execute(s->new OperationInputStore(f.runtime()).prepare(f.org(),f.execution(),"external",logical,"c".repeat(64),"rest","update","WRITE",new OperationApprovalIntent.Connection(connection,0,1,"update"),new GraphDefinition().parse("{}"),now));migrate(f.url(),"18").migrate();
        var approvals=new com.bridgeops.approvals.persistence.ApprovalStore(f.runtime());approvals.ensurePolicy(f.org(),now);var e=new ExecutionStore(f.runtime()).get(f.org(),f.execution(),false);
        for(int changed=0;changed<3;changed++){
            var intent=OperationApprovalIntent.create(f.org(),f.execution(),"external","external",f.version(),e.hash(),com.bridgeops.shared.domain.ActorReference.user(f.actor()),logical,prepared.id(),"rest","update",changed==2?"READ":"WRITE",new OperationApprovalIntent.Connection(connection,changed==1?1:0,1,"update"),new GraphDefinition().parse("{}"),0,new GraphDefinition().parse(approvals.policy(f.org()).snapshot()),"Review exact configured operation",now,now.plusSeconds(600),e.deadline());
            if(changed==0)assertThat(approvals.insert(intent,"c".repeat(64)).operationIntent()).isEqualTo(intent);
            else assertThatThrownBy(()->approvals.insert(intent,"c".repeat(64))).isInstanceOfSatisfying(org.springframework.dao.DataIntegrityViolationException.class,failure->assertThat(((java.sql.SQLException)failure.getMostSpecificCause()).getSQLState()).isEqualTo("23514"));
        }
    }
    @Test void schema2MigrationPreservesPopulatedLegacyApprovalBytesAndDecisions(){
        String url=database();migrate(url,"11").migrate();var db=new JdbcTemplate(source(url,false));new GraphDataMigrationIT().seedV11(db);
        var requests=db.queryForList("SELECT to_jsonb(r)::text AS row FROM approval_requests r");var decisions=db.queryForList("SELECT * FROM approval_decisions");
        assertThat(migrate(url,"18").migrate().migrationsExecuted).isEqualTo(7);
        assertThat(db.queryForList("SELECT (to_jsonb(r)-ARRAY['intent_schema_version','prepared_intent_id'])::text AS row FROM approval_requests r")).containsExactlyInAnyOrderElementsOf(requests);assertThat(db.queryForList("SELECT * FROM approval_decisions")).containsExactlyInAnyOrderElementsOf(decisions);
        var stored=new com.bridgeops.approvals.persistence.ApprovalStore(db);UUID org=db.queryForObject("SELECT organization_id FROM approval_requests",UUID.class),id=db.queryForObject("SELECT id FROM approval_requests",UUID.class);assertThat(stored.get(org,id,false).generalized()).isFalse();
    }
    @Test void tenantStepContextAndAppendOnlyRuntimePrivilegesAreEnforced(){
        var f=fixture();prepare(f,"{\"message\":\"immutable\"}");for(String table:List.of("execution_operation_bindings","execution_operation_inputs")){for(String privilege:List.of("UPDATE","DELETE","TRUNCATE"))assertThat(f.db().queryForObject("SELECT has_table_privilege('bridgeops_app',?,?)",Boolean.class,table,privilege)).isFalse();assertThatThrownBy(()->f.db().update("UPDATE "+table+" SET prepared_at=now()")).isInstanceOf(org.springframework.dao.DataAccessException.class);}
        UUID foreign=new OrganizationStore(f.db()).insert(f.actor(),"Foreign","foreign-operation",Instant.now()).id();assertThatThrownBy(()->f.tx().execute(s->new OperationInputStore(f.runtime()).prepare(foreign,f.execution(),"positive",f.operation(),f.blueprint(),"synthetic","record","WRITE",null,new GraphDefinition().parse("{}"),Instant.now()))).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(()->f.runtime().update("INSERT INTO execution_operation_inputs SELECT organization_id,execution_id,'forged',gen_random_uuid(),logical_operation_id,graph_schema_version,blueprint_hash,argument_hash,capability,operation,classification,prepared_at FROM execution_operation_inputs")).isInstanceOf(org.springframework.dao.DataAccessException.class);
    }
}
