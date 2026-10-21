package com.bridgeops.integrations;

import com.bridgeops.integrations.domain.RestOperation;
import com.bridgeops.shared.web.DomainFailure;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class RestOperationTest {
    final JsonMapper json=JsonMapper.builder().build();
    final String definition="""
        {"id":"create","method":"POST","path":"/v1/items/{id}","pathParameters":["id"],"requestFields":{"message":"STRING","count":"INTEGER","enabled":"BOOLEAN"},"responseFields":{"id":"STRING"},"classification":"WRITE","timeoutSeconds":5,"maximumResponseBytes":4096,"remoteIdempotencyKey":true}
        """;
    @Test void registeredOperationRejectsAuthorityFieldsAndUnsupportedMethods(){for(String invalid:List.of(definition.replace("\"POST\"","\"CONNECT\""),definition.replace("\"WRITE\"","\"READ\""),definition.replace("\"timeoutSeconds\":5","\"timeoutSeconds\":\"5\""),definition.replace("\"remoteIdempotencyKey\":true","\"remoteIdempotencyKey\":\"true\""),definition.replace("\"id\":\"create\"","\"id\":\"create\",\"headers\":{}")))assertThatThrownBy(()->RestOperation.parse(json.readTree(invalid))).isInstanceOf(DomainFailure.class);}
    @Test void mappedRequestUsesExactDeclaredFieldsAndTypesWithoutCoercion(){var operation=RestOperation.parse(json.readTree(definition));String arguments="{\"pathParameters\":{\"id\":\"ticket_1\"},\"fields\":{\"message\":\"hello\",\"count\":1,\"enabled\":true}}";assertThatCode(()->operation.validateArguments(json.readTree(arguments))).doesNotThrowAnyException();for(String invalid:List.of(arguments.replace("\"count\":1","\"count\":\"1\""),arguments.replace("\"count\":1","\"count\":1.5"),arguments.replace("\"enabled\":true","\"enabled\":\"true\""),arguments.replace("\"message\":\"hello\"","\"message\":7"),arguments.replace("\"count\":1","\"count\":1,\"unapproved\":true")))assertThatThrownBy(()->operation.validateArguments(json.readTree(invalid))).isInstanceOf(DomainFailure.class);}
    @Test void responseProjectionDropsUnapprovedDataAndRejectsWrongTypes(){var operation=RestOperation.parse(json.readTree(definition));var result=operation.projectResponse(json.readTree("{\"id\":\"created\",\"password\":\"canary-unselected\",\"private\":{\"large\":true}}"));assertThat(result.toString()).isEqualTo("{\"id\":\"created\"}");assertThatThrownBy(()->operation.projectResponse(json.readTree("{\"id\":3}"))).isInstanceOf(DomainFailure.class);assertThatThrownBy(()->operation.projectResponse(json.readTree("[]"))).isInstanceOf(DomainFailure.class);}
    @Test void sensitiveProjectionNamesAndParameterTraversalAreForbidden(){for(String field:List.of("password","PASSWORD","accessToken","api_key","credential","constructor"))assertThatThrownBy(()->RestOperation.parse(json.readTree(definition.replace("\"responseFields\":{\"id\":\"STRING\"}","\"responseFields\":{\""+field+"\":\"STRING\"}")))).isInstanceOf(DomainFailure.class);var operation=RestOperation.parse(json.readTree(definition));for(String value:List.of("../admin","a/b","%2e%2e","x?admin=true","x\r\nHost:evil"))assertThatThrownBy(()->operation.resolvedPath(json.valueToTree(Map.of("pathParameters",Map.of("id",value),"fields",Map.of("message","hello","count",1,"enabled",true))))).isInstanceOf(DomainFailure.class);}
}
