package com.bridgeops.integrations;

import com.bridgeops.integrations.domain.WebhookPayload;
import com.bridgeops.shared.web.DomainFailure;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class WebhookPayloadTest {
    byte[] bytes(String value){return value.getBytes(StandardCharsets.UTF_8);}
    @Test void acceptsOnlyFlatTypedInputAndPreservesUnicode(){assertThat(WebhookPayload.parse(bytes("{\"input\":{\"count\":1,\"active\":true,\"label\":\"Team 😀\"}}" )).path("label").asString()).isEqualTo("Team 😀");assertThat(WebhookPayload.parse(bytes("{\"input\":{}}" )).isEmpty()).isTrue();}
    @Test void rejectsTrailingValuesDuplicateNamesAndNestedConfiguration(){
        for(String value:new String[]{"{\"input\":{}} {}","{\"input\":{}} true","{\"input\":{},\"input\":{}}","{\"input\":{\"a\":1,\"a\":2}}","{\"input\":{\"a\":[]}}","{\"input\":{\"a\":{}}}","{\"input\":{\"a\":null}}","{\"input\":{\"a\":1.5}}","{\"input\":{},\"authority\":true}","[{}]"})assertThatThrownBy(()->WebhookPayload.parse(bytes(value))).as(value).isInstanceOfSatisfying(DomainFailure.class,e->assertThat(e.status()).isEqualTo(400));
    }
    @Test void rejectsInvalidUnicodeAndBounds(){
        for(String value:new String[]{"\\u0000","\\ud800","\\udfff"})assertThatThrownBy(()->WebhookPayload.parse(bytes("{\"input\":{\"a\":\""+value+"\"}}"))).isInstanceOf(DomainFailure.class);
        assertThatThrownBy(()->WebhookPayload.parse(new byte[]{(byte)0xc3,0x28})).isInstanceOf(DomainFailure.class);assertThatThrownBy(()->WebhookPayload.parse(new byte[262145])).isInstanceOfSatisfying(DomainFailure.class,e->assertThat(e.status()).isEqualTo(413));assertThatThrownBy(()->WebhookPayload.parse(bytes("{\"input\":{\"a\":\""+"x".repeat(2001)+"\"}}"))).isInstanceOf(DomainFailure.class);
    }
}
