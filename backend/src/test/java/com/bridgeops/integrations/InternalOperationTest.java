package com.bridgeops.integrations;

import com.bridgeops.integrations.domain.InternalOperation;
import com.bridgeops.shared.web.DomainFailure;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class InternalOperationTest {
    final JsonMapper json=JsonMapper.builder().build();
    String args(String capability,String operation,String value){return InternalOperation.arguments(capability,operation,json.readTree(value));}
    String assignment(UUID id,long revision){return "{\"ticketId\":\""+id+"\",\"expectedRevision\":"+revision+",\"currentTeamId\":null,\"targetTeamId\":\"11111111-1111-4111-8111-111111111111\"}";}
    void invalid(String capability,String operation,String value){assertThatThrownBy(()->args(capability,operation,value)).isInstanceOfSatisfying(DomainFailure.class,e->{assertThat(e.status()).isEqualTo(422);assertThat(e.code()).isEqualTo("INVALID_CAPABILITY_INPUT");assertThat(e.toString()).doesNotContain("private-canary");});}
    @Test void normalizesListDefaultsAndFilterSetsWithoutCoercingFields(){
        var defaults=json.readTree(args("support","list","{}"));assertThat(defaults.path("limit").asInt()).isEqualTo(50);assertThat(defaults.path("priorities").size()).isEqualTo(4);assertThat(defaults.path("statuses").size()).isEqualTo(4);
        assertThat(args("support","list","{\"priorities\":[\"URGENT\",\"HIGH\"],\"statuses\":[\"OPEN\"],\"limit\":5}")).isEqualTo(args("support","list","{\"limit\":5,\"statuses\":[\"OPEN\"],\"priorities\":[\"HIGH\",\"URGENT\"]}"));
        for(String value:List.of("{\"limit\":\"5\"}","{\"limit\":0}","{\"limit\":51}","{\"limit\":1.5}","{\"priorities\":[\"HIGH\",\"HIGH\"]}","{\"statuses\":[]}","{\"priorities\":[\"high\"]}","{\"url\":\"private-canary\"}"))invalid("support","list",value);
    }
    @Test void exactBatchSnapshotsKeepNullPriorTeamAndPreserveItemOrder(){
        UUID a=UUID.randomUUID(),b=UUID.randomUUID();String first=args("support","assignBatch","{\"assignments\":["+assignment(a,7)+","+assignment(b,2)+"]}");assertThat(first).contains("\"currentTeamId\":null","\"expectedRevision\":7");assertThat(first).isNotEqualTo(args("support","assignBatch","{\"assignments\":["+assignment(b,2)+","+assignment(a,7)+"]}"));
        var fifty=json.createArrayNode();for(int i=0;i<50;i++)fifty.add(json.readTree(assignment(UUID.randomUUID(),0)));var root=json.createObjectNode();root.set("assignments",fifty);assertThat(json.readTree(InternalOperation.arguments("support","assignBatch",root)).path("assignments").size()).isEqualTo(50);fifty.add(json.readTree(assignment(UUID.randomUUID(),0)));invalid("support","assignBatch",root.toString());
    }
    @Test void duplicateTicketsMissingPriorStateAndChangedArgumentTypesAreRejected(){
        String item=assignment(UUID.randomUUID(),1);for(String value:List.of("{\"assignments\":[]}","{\"assignments\":["+item+","+item+"]}","{\"assignments\":["+item.replace("\"currentTeamId\":null,","")+"]}","{\"assignments\":["+item.replace("\"expectedRevision\":1","\"expectedRevision\":\"1\"")+"]}","{\"assignments\":["+item.replace("\"expectedRevision\":1","\"expectedRevision\":9223372036854775807")+"]}"))invalid("support","assignBatch",value);
    }
    @Test void identifiersMustBeFullUuidsAndBoundedTextCannotCarryControlCharacters(){
        invalid("support","get","{\"ticketId\":\"1-1-1-1-1\"}");String id="AAAAAAAA-AAAA-4AAA-8AAA-AAAAAAAAAAAA";assertThat(args("support","get","{\"ticketId\":\""+id+"\"}")).contains(id.toLowerCase(Locale.ROOT));
        for(String value:List.of("{\"message\":\" \"}","{\"message\":\"private-canary\\u0000\"}","{\"message\":\""+"x".repeat(501)+"\"}"))invalid("notifications","create",value);
        assertThat(args("notifications","create","{\"message\":\"Line one\\nLine two\"}")).contains("Line one\\nLine two");
    }
    @Test void resolutionSnapshotsAreTypedBoundedAndDoNotAcceptUnspecifiedMutableFields(){
        assertThat(args("teams","resolve","{\"tickets\":[]}")).isEqualTo("{\"tickets\":[]}");String ticket="{\"ticketId\":\""+UUID.randomUUID()+"\",\"title\":\"Fixture\",\"revision\":0,\"currentTeamId\":null,\"status\":\"OPEN\",\"priority\":\"HIGH\",\"category\":\"ACCESS\"}";
        assertThat(json.readTree(args("teams","resolve","{\"tickets\":["+ticket+"]}")).path("tickets").size()).isEqualTo(1);invalid("teams","resolve","{\"tickets\":["+ticket+","+ticket+"]}");invalid("teams","resolve","{\"tickets\":["+ticket.replace("\"revision\":0","\"revision\":false")+"]}");
    }
    @Test void fixedOperationClassificationAndSchemasHaveNoDynamicExecutionSurface(){
        assertThat(InternalOperation.definition("support","assignBatch").requiresApproval()).isTrue();assertThat(InternalOperation.definition("support","addNote").classification()).isEqualTo("WRITE");assertThat(InternalOperation.definition("notifications","create").requiresApproval()).isFalse();assertThat(InternalOperation.definition("teams","resolve").classification()).isEqualTo("READ");
        assertThatThrownBy(()->InternalOperation.definition("support","executeSql")).isInstanceOf(DomainFailure.class);assertThatThrownBy(()->InternalOperation.definition("teams","get").inputs().clear()).isInstanceOf(UnsupportedOperationException.class);invalid("teams","list","{\"expression\":\"private-canary\"}");
    }
}
