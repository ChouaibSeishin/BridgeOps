package com.bridgeops.integrations;

import com.bridgeops.integrations.domain.WebhookSignature;
import com.bridgeops.shared.web.DomainFailure;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class WebhookSignatureTest {
    final byte[] key=new byte[32];final Instant now=Instant.parse("2026-10-23T12:00:00Z");final String timestamp=Long.toString(now.getEpochSecond()),delivery="fixture-delivery_1";
    final byte[] body="{\"limit\":2}".getBytes(StandardCharsets.UTF_8);
    String sign(String timestamp,String delivery,byte[] body){try{var mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(key,"HmacSHA256"));mac.update(("v1\n"+timestamp+"\n"+delivery+"\n").getBytes(StandardCharsets.UTF_8));return HexFormat.of().formatHex(mac.doFinal(body));}catch(Exception e){throw new AssertionError(e);}}
    void denied(Runnable action){assertThatThrownBy(action::run).isInstanceOfSatisfying(DomainFailure.class,e->{assertThat(e.status()).isEqualTo(401);assertThat(e.code()).isEqualTo("INVALID_WEBHOOK_SIGNATURE");assertThat(e.toString()).doesNotContain(delivery,new String(body,StandardCharsets.UTF_8));});}
    @Test void verifiesExactRawBytesAndUnambiguousPrefix(){WebhookSignature.verify(key,timestamp,delivery,sign(timestamp,delivery,body),body,now);byte[] malformedJson="not JSON".getBytes(StandardCharsets.UTF_8);WebhookSignature.verify(key,timestamp,delivery,sign(timestamp,delivery,malformedJson),malformedJson,now);}
    @Test void changedBodyWhitespaceDeliveryTimestampAndKeyFail(){String signature=sign(timestamp,delivery,body);denied(()->WebhookSignature.verify(key,timestamp,delivery,signature,"{ \"limit\":2}".getBytes(StandardCharsets.UTF_8),now));denied(()->WebhookSignature.verify(key,timestamp,delivery+"2",signature,body,now));denied(()->WebhookSignature.verify(key,Long.toString(now.getEpochSecond()+1),delivery,signature,body,now));byte[] other=key.clone();other[0]=1;denied(()->WebhookSignature.verify(other,timestamp,delivery,signature,body,now));}
    @Test void exactSkewBoundsAreInclusiveAndDoNotUseWallClock(){for(long offset:new long[]{-300,300}){String seconds=Long.toString(now.getEpochSecond()+offset);WebhookSignature.verify(key,seconds,delivery,sign(seconds,delivery,body),body,now);}for(long offset:new long[]{-301,301}){String seconds=Long.toString(now.getEpochSecond()+offset);denied(()->WebhookSignature.verify(key,seconds,delivery,sign(seconds,delivery,body),body,now));}}
    @Test void rejectsNonCanonicalAndInjectedHeaders(){for(String invalid:List.of("0"+timestamp,"+"+timestamp," "+timestamp,timestamp+"\n","-1","1.0","9999999999999",""))denied(()->WebhookSignature.verify(key,invalid,delivery,sign(invalid,delivery,body),body,now));for(String invalid:List.of("", "-start", "line\nheader", "a".repeat(129), "résumé", "with space"))denied(()->WebhookSignature.verify(key,timestamp,invalid,sign(timestamp,invalid,body),body,now));}
    @Test void rejectsMalformedSignaturesAndOversizedBodiesBeforeTrustingContent(){for(String invalid:List.of("", "0".repeat(63),"0".repeat(65),"G".repeat(64),"SHA256="+sign(timestamp,delivery,body),sign(timestamp,delivery,body).toUpperCase(Locale.ROOT)))denied(()->WebhookSignature.verify(key,timestamp,delivery,invalid,body,now));byte[] large=new byte[WebhookSignature.MAX_BODY+1];denied(()->WebhookSignature.verify(key,timestamp,delivery,sign(timestamp,delivery,large),large,now));}
    @Test void acceptsBoundedEmptyAndMaximumRawBodiesWithoutInventingInputSemantics(){for(byte[] value:new byte[][]{new byte[0],new byte[WebhookSignature.MAX_BODY]})WebhookSignature.verify(key,timestamp,delivery,sign(timestamp,delivery,value),value,now);}
}
