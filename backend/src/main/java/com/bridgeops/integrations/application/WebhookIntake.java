package com.bridgeops.integrations.application;

import com.bridgeops.integrations.domain.WebhookSignature;
import com.bridgeops.integrations.domain.WebhookPayload;
import com.bridgeops.integrations.persistence.WebhookDeliveryStore;
import com.bridgeops.shared.web.DomainFailure;
import java.security.*;
import java.time.Clock;
import java.util.*;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;
import tools.jackson.databind.JsonNode;

/** Raw-byte authentication precedes JSON interpretation; no transaction spans signature verification. */
@Service
public class WebhookIntake {
    record Verified(WebhookDeliveryStore.Route route,String delivery,String payloadHash,long signedSeconds,JsonNode input){
        @Override public String toString(){return "VerifiedWebhook[REDACTED]";}
    }
    public record Accepted(UUID organization,UUID execution,java.time.Instant acceptedAt,boolean replay){}
    private final WebhookDeliveryStore deliveries;private final WebhookAdmission admission;private final SecretStore secrets;
    private final WebhookAcceptance acceptance;private final Clock clock;
    public WebhookIntake(WebhookDeliveryStore deliveries,WebhookAdmission admission,SecretStore secrets,WebhookAcceptance acceptance,Clock clock){this.deliveries=deliveries;this.admission=admission;this.secrets=secrets;this.acceptance=acceptance;this.clock=clock;}
    public Accepted receive(UUID endpoint,String timestamp,String delivery,String signature,byte[] raw){
        if(raw==null||raw.length>WebhookSignature.MAX_BODY)throw new DomainFailure(413,"WEBHOOK_BODY_TOO_LARGE");
        if(timestamp==null||delivery==null||signature==null)throw new DomainFailure(401,"INVALID_WEBHOOK_SIGNATURE");
        byte[] body=raw.clone();var route=deliveries.route(endpoint);var lease=admission.acquire(route);
        try{
            byte[] key=secrets.resolve(route.organization(),route.signingReference(),"WEBHOOK_SIGNING");
            try{WebhookSignature.verify(key,timestamp,delivery,signature,body,clock.instant());}finally{Arrays.fill(key,(byte)0);}
            var input=WebhookPayload.parse(body);
            return acceptance.accept(new Verified(route,delivery,digest(body),Long.parseLong(timestamp),input),lease);
        }finally{
            // Acceptance has already committed on return. Expiry recovers a release lost to a database outage.
            try{admission.release(lease);}catch(DataAccessException|TransactionException unavailable){/* bounded lease expires */}
            Arrays.fill(body,(byte)0);
        }
    }
    private String digest(byte[] body){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));}catch(NoSuchAlgorithmException impossible){throw new IllegalStateException("SHA-256 unavailable.");}}
}
