package com.bridgeops.integrations.domain;

import com.bridgeops.shared.web.DomainFailure;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Instant;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Exact wire protocol. Signature validation precedes parsing or trusting JSON. */
public final class WebhookSignature {
    public static final int MAX_BODY = 256 * 1024;
    private WebhookSignature() {}
    public static void verify(byte[] key,String timestamp,String delivery,String signature,byte[] body,Instant now) {
        if(key==null||key.length!=32||body==null||body.length>MAX_BODY||now==null
            ||timestamp==null||!timestamp.matches("[1-9][0-9]{0,11}")
            ||delivery==null||!delivery.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,127}")
            ||signature==null||!signature.matches("[a-f0-9]{64}"))throw invalid();
        long seconds;
        try{seconds=Long.parseLong(timestamp);}catch(NumberFormatException bad){throw invalid();}
        if(seconds<now.getEpochSecond()-300||seconds>now.getEpochSecond()+300)throw invalid();
        try{
            Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(key,"HmacSHA256"));
            mac.update(("v1\n"+timestamp+"\n"+delivery+"\n").getBytes(StandardCharsets.UTF_8));
            if(!MessageDigest.isEqual(mac.doFinal(body),HexFormat.of().parseHex(signature)))throw invalid();
        }catch(GeneralSecurityException impossible){throw new IllegalStateException("Webhook verification unavailable.");}
    }
    private static DomainFailure invalid(){return new DomainFailure(401,"INVALID_WEBHOOK_SIGNATURE");}
}
