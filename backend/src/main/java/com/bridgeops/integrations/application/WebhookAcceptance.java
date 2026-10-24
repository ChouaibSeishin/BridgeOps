package com.bridgeops.integrations.application;

import com.bridgeops.audit.application.IntegrationAudit;
import com.bridgeops.executions.application.*;
import com.bridgeops.integrations.persistence.*;
import com.bridgeops.shared.domain.AutomationOrigin;
import com.bridgeops.shared.web.DomainFailure;
import java.time.Clock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Commit the immutable delivery, execution, work and required audit as one acceptance boundary. */
@Service
public class WebhookAcceptance {
    private final WebhookDeliveryStore deliveries;private final WebhookStore endpoints;private final WebhookAdmission admission;
    private final ExecutionAutomation authority;private final WebhookExecutions executions;private final IntegrationAudit audit;private final Clock clock;
    public WebhookAcceptance(WebhookDeliveryStore deliveries,WebhookStore endpoints,WebhookAdmission admission,ExecutionAutomation authority,WebhookExecutions executions,IntegrationAudit audit,Clock clock){this.deliveries=deliveries;this.endpoints=endpoints;this.admission=admission;this.authority=authority;this.executions=executions;this.audit=audit;this.clock=clock;}
    @Transactional(timeout=10)
    WebhookIntake.Accepted accept(WebhookIntake.Verified verified,WebhookAdmission.Lease lease){
        var route=verified.route();var preview=endpoints.get(route.organization(),route.endpoint(),false);
        var origin=new AutomationOrigin(preview.principal(),preview.principalCreator(),preview.id(),route.revision(),endpoints.accepted(route.organization(),route.endpoint(),route.revision()).author());
        authority.authorize(route.organization(),origin,preview.workflow(),preview.version(),preview.hash());
        var row=endpoints.get(route.organization(),route.endpoint(),true);
        if(row.revision()!=route.revision()||!row.secret().equals(route.signingReference())||!row.status().equals("ACTIVE"))throw new DomainFailure(401,"INVALID_WEBHOOK_SIGNATURE");
        if(!lease.organization().equals(route.organization())||!lease.endpoint().equals(route.endpoint()))throw new DomainFailure(429,"WEBHOOK_BUSY");admission.consume(lease);
        var now=clock.instant();if(verified.signedSeconds()<now.getEpochSecond()-300||verified.signedSeconds()>now.getEpochSecond()+300)throw new DomainFailure(401,"INVALID_WEBHOOK_SIGNATURE");
        var prior=deliveries.receipt(row.organization(),row.id(),verified.delivery());
        if(prior.isPresent()){
            var receipt=prior.get();if(!receipt.payloadHash().equals(verified.payloadHash()))throw new DomainFailure(409,"WEBHOOK_DELIVERY_CONFLICT");
            return new WebhookIntake.Accepted(row.organization(),receipt.execution(),receipt.acceptedAt(),true);
        }
        String key=deliveryKey(row.id()+"\n"+verified.delivery());
        var accepted=executions.submit(row.organization(),origin,row.workflow(),row.version(),row.hash(),verified.input(),key);
        if(accepted.replay())throw new DomainFailure(409,"RECONCILIATION_REQUIRED");
        deliveries.insert(row.organization(),origin,row.workflow(),row.version(),row.hash(),verified.delivery(),verified.payloadHash(),verified.signedSeconds(),accepted.execution(),accepted.createdAt());
        audit.write(row.organization(),origin.actor(),IntegrationAudit.Action.WEBHOOK_ACCEPTED,row.id(),row.revision(),now);
        return new WebhookIntake.Accepted(row.organization(),accepted.execution(),accepted.createdAt(),false);
    }
    private String deliveryKey(String value){try{return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException("SHA-256 unavailable.");}}
}
