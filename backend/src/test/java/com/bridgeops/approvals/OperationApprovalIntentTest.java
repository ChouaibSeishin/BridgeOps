package com.bridgeops.approvals;

import com.bridgeops.approvals.application.*;
import com.bridgeops.shared.domain.ActorReference;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class OperationApprovalIntentTest {
    final JsonMapper json=JsonMapper.builder().build();
    final UUID org=UUID.randomUUID(),execution=UUID.randomUUID(),version=UUID.randomUUID(),human=UUID.randomUUID(),logical=UUID.randomUUID(),prepared=UUID.randomUUID(),connection=UUID.randomUUID();
    final Instant now=Instant.parse("2026-10-21T12:00:00Z");
    JsonNode args(){return json.readTree("{\"assignments\":[{\"ticketId\":\"11111111-1111-4111-8111-111111111111\",\"expectedRevision\":7,\"currentTeamId\":null,\"targetTeamId\":\"22222222-2222-4222-8222-222222222222\"}]}");}
    JsonNode policy(){return json.readTree("{\"required\":false,\"lifetimeSeconds\":3600}");}
    OperationApprovalIntent intent(JsonNode arguments){return OperationApprovalIntent.create(org,execution,"gate","assign",version,"a".repeat(64),ActorReference.user(human),logical,prepared,"support","assignBatch","WRITE",new OperationApprovalIntent.Connection(connection,0,1,"assignBatch"),arguments,0,policy(),"Review exact assignments",now,now.plusSeconds(3600),now.plusSeconds(86400));}
    @Test void canonicalBatchPreservesNullPriorTeamAndExactRevisionsWithoutRetainingMutableInputs(){
        var arguments=args();var value=intent(arguments);assertThat(value.argumentHash()).isEqualTo(ApprovalIntent.digest(value.arguments()));assertThat(value.hash()).isEqualTo(ApprovalIntent.digest(value.canonical()));assertThat(value.canonical()).contains("\"schemaVersion\":2","\"preparedIntent\":\""+prepared,"\"currentTeamId\":null","\"expectedRevision\":7");
        ((tools.jackson.databind.node.ObjectNode)arguments.path("assignments").get(0)).put("expectedRevision",8);assertThat(value.arguments()).contains("\"expectedRevision\":7");assertThat(intent(arguments).hash()).isNotEqualTo(value.hash());
    }
    @Test void canonicalObjectOrderIsStableButBatchOrderIsPartOfIntent(){
        var a=intent(args());var reordered=json.readTree("{\"assignments\":[{\"targetTeamId\":\"22222222-2222-4222-8222-222222222222\",\"currentTeamId\":null,\"expectedRevision\":7,\"ticketId\":\"11111111-1111-4111-8111-111111111111\"}]}");assertThat(intent(reordered)).isEqualTo(a);
        var list=(tools.jackson.databind.node.ArrayNode)reordered.path("assignments");var second=(tools.jackson.databind.node.ObjectNode)list.get(0).deepCopy();second.put("expectedRevision",9);list.add(second);String first=intent(reordered).hash();var firstItem=list.remove(0);list.add(firstItem);assertThat(intent(reordered).hash()).isNotEqualTo(first);
    }
    @Test void restoresOnlyAnExactBoundedCanonicalValueAndNeverEchoesParserInputs(){
        var value=intent(args());assertThat(OperationApprovalIntent.restore(value.canonical())).isEqualTo(value);
        for(String invalid:List.of(value.canonical()+" ",value.canonical().replace("\"schemaVersion\":2","\"schemaVersion\":1"),value.canonical().replace("\"operationRuleVersion\":1","\"operationRuleVersion\":2"),value.canonical().replace("\"policyVersion\":0","\"policyVersion\":\"0\""),"invalid-private-canary","x".repeat(OperationApprovalIntent.MAX_CANONICAL_BYTES+1)))assertThatThrownBy(()->OperationApprovalIntent.restore(invalid)).isInstanceOf(IllegalArgumentException.class).hasMessage("Invalid operation approval intent.");
    }
    @Test void identitySnapshotOperationAndConnectionRevisionAreAllBound(){
        var baseline=intent(args());for(int changed=0;changed<11;changed++){
            var value=OperationApprovalIntent.create(changed==0?UUID.randomUUID():org,changed==1?UUID.randomUUID():execution,changed==2?"anotherGate":"gate",changed==3?"anotherAction":"assign",changed==4?UUID.randomUUID():version,changed==5?"b".repeat(64):"a".repeat(64),ActorReference.user(changed==6?UUID.randomUUID():human),changed==7?UUID.randomUUID():logical,changed==8?UUID.randomUUID():prepared,"support","assignBatch","WRITE",new OperationApprovalIntent.Connection(changed==9?UUID.randomUUID():connection,changed==10?1:0,1,"assignBatch"),args(),0,policy(),"Review exact assignments",now,now.plusSeconds(3600),now.plusSeconds(86400));assertThat(value.hash()).as("bound field %s",changed).isNotEqualTo(baseline.hash());
        }
    }
    @Test void actualAutomationIdentityAndBothConfiguringHumansAreCanonicalAndIneligibleToDecide(){
        UUID principal=UUID.randomUUID(),creator=UUID.randomUUID(),endpointAuthor=UUID.randomUUID();var actor=ActorReference.automation(principal,creator,endpointAuthor);assertThat(actor.type()).isEqualTo(ActorReference.Type.AUTOMATION);assertThat(actor.id()).isEqualTo(principal);assertThat(actor.excludes(creator)).isTrue();assertThat(actor.excludes(endpointAuthor)).isTrue();assertThat(actor.excludes(UUID.randomUUID())).isFalse();assertThat(ActorReference.user(human).excludes(human)).isTrue();
        var value=OperationApprovalIntent.create(org,execution,"gate","assign",version,"a".repeat(64),actor,logical,prepared,"support","assignBatch","WRITE",new OperationApprovalIntent.Connection(connection,0,1,"assignBatch"),args(),0,policy(),"Review exact assignments",now,now.plusSeconds(3600),now.plusSeconds(86400));assertThat(value.canonical()).contains("\"type\":\"AUTOMATION\"",principal.toString(),creator.toString(),endpointAuthor.toString()).doesNotContain("\"requester\"");
        assertThat(ActorReference.automation(principal,endpointAuthor,creator)).isEqualTo(actor);assertThat(ActorReference.automation(principal,creator,creator).configuringHumans()).containsExactly(creator);
    }
    @Test void actorReferencesCopyListsAndRejectMissingOrInventedHumanContext(){
        var original=new ArrayList<>(List.of(human));var actor=new ActorReference(ActorReference.Type.AUTOMATION,UUID.randomUUID(),original);original.clear();assertThat(actor.configuringHumans()).containsExactly(human);assertThatThrownBy(()->actor.configuringHumans().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(()->new ActorReference(ActorReference.Type.USER,human,List.of(human))).isInstanceOf(IllegalArgumentException.class);assertThatThrownBy(()->new ActorReference(ActorReference.Type.AUTOMATION,human,List.of())).isInstanceOf(IllegalArgumentException.class);assertThatThrownBy(()->ActorReference.automation(human,null,human)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void permanentOperationClassificationCannotBeDowngradedToReadAndConnectionIsExplicit(){
        assertThatThrownBy(()->OperationApprovalIntent.create(org,execution,"gate","assign",version,"a".repeat(64),ActorReference.user(human),logical,prepared,"support","assignBatch","READ",new OperationApprovalIntent.Connection(connection,0,1,"assignBatch"),args(),0,policy(),"Review",now,now.plusSeconds(3600),now.plusSeconds(86400))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->OperationApprovalIntent.create(org,execution,"gate","assign",version,"a".repeat(64),ActorReference.user(human),logical,prepared,"support","assignBatch","WRITE",null,args(),0,policy(),"Review",now,now.plusSeconds(3600),now.plusSeconds(86400))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new OperationApprovalIntent.Connection(connection,-1,1,"assignBatch")).isInstanceOf(IllegalArgumentException.class);assertThatThrownBy(()->new OperationApprovalIntent.Connection(connection,0,1,"https://evil.invalid")).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void argumentDepthBatchBytesAndPrimitiveTypesAreBoundedWithoutEchoingValues(){
        var list=json.createArrayNode();for(int i=0;i<51;i++)list.add(i);var root=json.createObjectNode();root.set("items",list);assertThatThrownBy(()->intent(root)).isInstanceOf(IllegalArgumentException.class);
        for(String invalid:List.of("[]","null","{\"revision\":1.5}","{\"revision\":18446744073709551617}","{\"arbitrary.path\":true}"))assertThatThrownBy(()->intent(json.readTree(invalid))).isInstanceOf(IllegalArgumentException.class);
        var large=json.createObjectNode();large.put("value","fixture-private-canary-"+"x".repeat(300000));assertThatThrownBy(()->intent(large)).isInstanceOf(IllegalArgumentException.class).hasMessage("Invalid operation approval intent.");assertThat(intent(args()).toString()).isEqualTo("OperationApprovalIntent[REDACTED]");
        JsonNode deep=json.createObjectNode();for(int i=0;i<18;i++){var parent=json.createObjectNode();parent.set("child",deep);deep=parent;}final var tooDeep=deep;assertThatThrownBy(()->intent(tooDeep)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void expiryPolicyAndMicrosecondPrecisionAreBoundRatherThanResettable(){
        var baseline=intent(args());var changed=OperationApprovalIntent.create(org,execution,"gate","assign",version,"a".repeat(64),ActorReference.user(human),logical,prepared,"support","assignBatch","WRITE",new OperationApprovalIntent.Connection(connection,0,1,"assignBatch"),args(),1,policy(),"Review exact assignments",now,now.plusSeconds(3599),now.plusSeconds(86400));assertThat(changed.hash()).isNotEqualTo(baseline.hash());assertThat(changed.argumentHash()).isEqualTo(baseline.argumentHash());
        assertThatThrownBy(()->OperationApprovalIntent.create(org,execution,"gate","assign",version,"a".repeat(64),ActorReference.user(human),logical,prepared,"support","assignBatch","WRITE",new OperationApprovalIntent.Connection(connection,0,1,"assignBatch"),args(),0,policy(),"Review",now,now.plusSeconds(3601),now.plusSeconds(86400))).isInstanceOf(IllegalArgumentException.class);
        var precision=OperationApprovalIntent.create(org,execution,"gate","assign",version,"a".repeat(64),ActorReference.user(human),logical,prepared,"support","assignBatch","WRITE",new OperationApprovalIntent.Connection(connection,0,1,"assignBatch"),args(),0,policy(),"Review exact assignments",now.plusNanos(123),now.plusSeconds(3600).plusNanos(456),now.plusSeconds(86400).plusNanos(789));assertThat(precision.canonical()).isEqualTo(baseline.canonical());
        assertThatThrownBy(()->OperationApprovalIntent.create(org,execution,"gate","assign",version,"a".repeat(64),ActorReference.user(human),logical,prepared,"support","assignBatch","WRITE",new OperationApprovalIntent.Connection(connection,0,1,"assignBatch"),args(),0,policy(),"Review",now,now.plusNanos(999),now.plusSeconds(86400))).isInstanceOf(IllegalArgumentException.class);
    }
}
