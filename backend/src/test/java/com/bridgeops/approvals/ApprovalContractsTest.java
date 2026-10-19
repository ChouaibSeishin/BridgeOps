package com.bridgeops.approvals;

import com.bridgeops.approvals.application.ApprovalIntent;
import com.bridgeops.approvals.domain.ApprovalState;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ApprovalContractsTest {
    final UUID org=UUID.randomUUID(),execution=UUID.randomUUID(),version=UUID.randomUUID(),requester=UUID.randomUUID(),operation=UUID.randomUUID();
    final Instant now=Instant.parse("2026-10-19T00:00:00Z");
    ApprovalIntent intent(String message,long policy,Instant expiry) {
        return ApprovalIntent.record(org,execution,"gate","action",version,"a".repeat(64),requester,operation,policy,
            "{\"required\":false,\"lifetimeSeconds\":3600}","Review exact internal record",message,now,expiry,now.plusSeconds(86400));
    }
    @Test void onlyPendingCanBecomeOneFinalDisposition() {
        for(var from:ApprovalState.values())for(var to:ApprovalState.values())
            assertThat(from.canTransitionTo(to)).isEqualTo(from==ApprovalState.PENDING&&to!=ApprovalState.PENDING);
        assertThatThrownBy(()->ApprovalState.APPROVED.requireTransition(ApprovalState.REJECTED)).isInstanceOf(IllegalStateException.class);
        assertThat(ApprovalState.PENDING.canTransitionTo(null)).isFalse();
    }
    @Test void exactUnicodeAndEscapedArgumentsSurviveCanonicalEncoding() {
        var first=intent("Review \"café\"\nline two",0,now.plusSeconds(3600));
        assertThat(first.arguments()).isEqualTo("{\"message\":\"Review \\\"café\\\"\\nline two\"}");
        assertThat(first.hash()).isEqualTo(intent("Review \"café\"\nline two",0,now.plusSeconds(3600)).hash()).hasSize(64);
        assertThat(first.canonical()).contains("\"capability\":\"synthetic\"","\"operation\":\"record\"");
    }
    @Test void changingArgumentsPolicyOrExpiryInvalidatesTheExactIntent() {
        var original=intent("Record this",0,now.plusSeconds(3600));
        assertThat(intent("Record another",0,now.plusSeconds(3600)).hash()).isNotEqualTo(original.hash());
        assertThat(intent("Record this",1,now.plusSeconds(3600)).hash()).isNotEqualTo(original.hash());
        assertThat(intent("Record this",0,now.plusSeconds(3599)).hash()).isNotEqualTo(original.hash());
        assertThat(intent("Record this",1,now.plusSeconds(3600)).argumentHash()).isEqualTo(original.argumentHash());
    }
    @Test void identityVersionNodeAndLogicalOperationAreBoundRatherThanOnlyThePrompt() {
        var original=intent("Record this",0,now.plusSeconds(3600));
        for(int field=0;field<8;field++) {
            var changed=ApprovalIntent.record(field==0?UUID.randomUUID():org,field==1?UUID.randomUUID():execution,
                field==2?"other-gate":"gate",field==3?"other-action":"action",field==4?UUID.randomUUID():version,
                field==5?"b".repeat(64):"a".repeat(64),field==6?UUID.randomUUID():requester,field==7?UUID.randomUUID():operation,
                0,"{\"required\":false,\"lifetimeSeconds\":3600}","Review exact internal record","Record this",now,now.plusSeconds(3600),now.plusSeconds(86400));
            assertThat(changed.hash()).isNotEqualTo(original.hash());
        }
    }
    @Test void expiredOrBeyondExecutionDeadlineIntentsAndOversizedArgumentsAreRejected() {
        for(Instant expiry:List.of(now,now.minusSeconds(1),now.plusSeconds(86401)))
            assertThatThrownBy(()->intent("Record",0,expiry)).isInstanceOf(IllegalArgumentException.class);
        for(String message:List.of(""," ","x".repeat(501)))assertThatThrownBy(()->intent(message,0,now.plusSeconds(3600))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void policyPropertyOrderAndDatabaseTimestampPrecisionDoNotChangeIntent() {
        var original=intent("Record this",0,now.plusSeconds(3600));
        var reordered=ApprovalIntent.record(org,execution,"gate","action",version,"a".repeat(64),requester,operation,0,
            "{\"lifetimeSeconds\":3600,\"required\":false}","Review exact internal record","Record this",now.plusNanos(123),now.plusSeconds(3600).plusNanos(123),now.plusSeconds(86400));
        assertThat(reordered.hash()).isEqualTo(original.hash());
        assertThat(reordered.policySnapshot()).isEqualTo("{\"lifetimeSeconds\":3600,\"required\":false}");
    }
}
