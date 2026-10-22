package com.bridgeops.approvals.application;

import com.bridgeops.approvals.persistence.ApprovalStore;
import com.bridgeops.audit.application.ApprovalAudit;
import com.bridgeops.organizations.application.TenantAuthorization;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class OperationApprovalGateTest {
    final UUID org=UUID.randomUUID(),execution=UUID.randomUUID(),actor=UUID.randomUUID();
    final Instant now=Instant.parse("2026-10-22T00:00:00Z");
    final ApprovalStore store=mock(ApprovalStore.class);
    final ApprovalRuntime runtime=new ApprovalRuntime(store,mock(ApprovalLifecycle.class),mock(ApprovalAudit.class),mock(TenantAuthorization.class),Clock.fixed(now,ZoneOffset.UTC));
    OperationApprovalGateTest(){
        when(store.action(any(),any(),anyString())).thenReturn(Optional.empty());when(store.policy(org)).thenReturn(new ApprovalStore.Policy(0,false,3600));
        when(store.insert(any(OperationApprovalIntent.class),anyString())).thenAnswer(call->{var r=mock(ApprovalStore.Request.class);when(r.organization()).thenReturn(org);when(r.id()).thenReturn(UUID.randomUUID());return r;});
    }
    ApprovalRuntime.OperationSeed seed(String capability,String operation,String classification){return new ApprovalRuntime.OperationSeed(org,execution,"action","action",UUID.randomUUID(),"a".repeat(64),actor,UUID.randomUUID(),"b".repeat(64),UUID.randomUUID(),capability,operation,classification,capability.equals("synthetic")?null:new OperationApprovalIntent.Connection(UUID.randomUUID(),0,1,operation),"{}","Review exact operation",now.plusSeconds(86400));}
    @Test void sensitiveWritesRequireARequestEvenWhenTheGraphOmitsAnApprovalAndRecordPolicyIsOff(){
        for(var operation:List.of(seed("support","assignBatch","WRITE"),seed("support","addNote","WRITE"),seed("rest","update","WRITE"))){var gate=runtime.check(operation,false,Set.of(actor));assertThat(gate.result()).isEqualTo(ApprovalRuntime.Result.WAIT);assertThat(gate.created()).isTrue();}
        verify(store,times(3)).insert(any(OperationApprovalIntent.class),anyString());
    }
    @Test void aSensitiveOperationCannotDowngradeItsClassificationToBypassTheGate(){
        for(String operation:List.of("assignBatch","addNote")){var gate=runtime.check(seed("support",operation,"READ"),false,Set.of(actor));assertThat(gate.result()).isEqualTo(ApprovalRuntime.Result.FAIL);assertThat(gate.error()).isEqualTo("INTENT_MISMATCH");}
        verify(store,never()).insert(any(OperationApprovalIntent.class),anyString());
    }
    @Test void lowRiskRegisteredOperationsStillPassTheCentralRuleAndExplicitGatesAreHonored(){
        assertThat(runtime.check(seed("support","list","READ"),false,Set.of(actor)).result()).isEqualTo(ApprovalRuntime.Result.ALLOW);
        assertThat(runtime.check(seed("notifications","create","WRITE"),false,Set.of(actor)).result()).isEqualTo(ApprovalRuntime.Result.ALLOW);
        assertThat(runtime.check(seed("notifications","create","WRITE"),true,Set.of(actor)).result()).isEqualTo(ApprovalRuntime.Result.WAIT);
        verify(store,times(1)).insert(any(OperationApprovalIntent.class),anyString());
    }
    @Test void theDeadlineAndDeclaredActorLocksAreRequiredBeforeAllowingDispatch(){
        var seed=seed("support","list","READ");assertThat(runtime.check(seed,false,Set.of()).result()).isEqualTo(ApprovalRuntime.Result.RECHECK_ACTORS);
        var expired=new ApprovalRuntime.OperationSeed(seed.organization(),seed.execution(),seed.gate(),seed.action(),seed.version(),seed.definitionHash(),seed.requester(),seed.operation(),seed.blueprintHash(),seed.prepared(),seed.capability(),seed.registeredOperation(),seed.classification(),seed.connection(),seed.arguments(),seed.prompt(),now);
        var gate=runtime.check(expired,false,Set.of(actor));assertThat(gate.result()).isEqualTo(ApprovalRuntime.Result.FAIL);assertThat(gate.error()).isEqualTo("DEADLINE_EXCEEDED");verify(store,never()).insert(any(OperationApprovalIntent.class),anyString());
    }
}
