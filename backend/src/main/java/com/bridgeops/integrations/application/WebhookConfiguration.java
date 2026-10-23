package com.bridgeops.integrations.application;

import com.bridgeops.audit.application.IntegrationAudit;
import com.bridgeops.integrations.persistence.WebhookStore;
import com.bridgeops.organizations.application.*;
import com.bridgeops.shared.web.DomainFailure;
import com.bridgeops.workflows.application.WorkflowService;
import java.security.SecureRandom;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Human configuration boundary. It does not accept deliveries or start executions. */
@Service
public class WebhookConfiguration {
    public record Endpoint(UUID id,String name,UUID principalId,UUID workflowId,UUID versionId,String definitionHash,long revision,String status,UUID createdBy,Instant createdAt,UUID revisionAuthor,Instant updatedAt){}
    public record Revealed(Endpoint endpoint,String signingSecret){@Override public String toString(){return "WebhookSecretReveal[REDACTED]";}}
    public record Page(List<Endpoint> items,int page,int size,long totalElements,long totalPages){}
    private final WebhookStore store;private final TenantAuthorization authority;private final AutomationAuthority automations;
    private final WorkflowService workflows;private final ConnectionService connections;private final SecretStore secrets;private final IntegrationAudit audit;private final Clock clock;
    private final SecureRandom random=new SecureRandom();
    public WebhookConfiguration(WebhookStore store,TenantAuthorization authority,AutomationAuthority automations,WorkflowService workflows,ConnectionService connections,SecretStore secrets,IntegrationAudit audit,Clock clock){this.store=store;this.authority=authority;this.automations=automations;this.workflows=workflows;this.connections=connections;this.secrets=secrets;this.audit=audit;this.clock=clock;}
    @Transactional(readOnly=true,timeout=5)
    public Endpoint get(UUID actor,UUID family,UUID org,UUID id){authority.read(actor,family,org,"integration.read");return view(store.get(org,id,false));}
    @Transactional(readOnly=true,timeout=5)
    public Page list(UUID actor,UUID family,UUID org,int page,int size){authority.read(actor,family,org,"integration.read");if(page<0||page>100000||size<1||size>100)throw new DomainFailure(400,"INVALID_REQUEST");long count=store.count(org);return new Page(store.list(org,page,size).stream().map(this::view).toList(),page,size,count,(count+size-1)/size);}
    @Transactional(timeout=10)
    public Revealed create(UUID actor,UUID family,UUID org,String name,UUID principal,boolean confirmed){
        configure(actor,family,org);confirm(confirmed);
        if(name==null||name.isBlank()||name.length()>120||name.codePoints().anyMatch(c->Character.isISOControl(c)||c>=0xD800&&c<=0xDFFF)||principal==null)throw new DomainFailure(400,"INVALID_REQUEST");
        var p=scope(actor,family,org,principal);if(store.count(org)>=1000)throw new DomainFailure(429,"WEBHOOK_ENDPOINT_LIMIT");
        byte[] key=new byte[32];random.nextBytes(key);
        try{UUID ref=secrets.put(actor,family,org,"WEBHOOK_SIGNING",key);Instant now=clock.instant().truncatedTo(ChronoUnit.MICROS);UUID id=UUID.randomUUID();
            store.create(new WebhookStore.Row(id,org,name.trim(),p.id(),p.createdBy(),p.workflowId(),p.versionId(),p.definitionHash(),0,"ACTIVE",ref,actor,now,actor,now));
            audit.write(org,actor,IntegrationAudit.Action.WEBHOOK_CREATED,id,0,now);return new Revealed(view(store.get(org,id,false)),Base64.getUrlEncoder().withoutPadding().encodeToString(key));
        }finally{Arrays.fill(key,(byte)0);}
    }
    @Transactional(timeout=10)
    public Revealed rotate(UUID actor,UUID family,UUID org,UUID id,long expected,boolean confirmed){
        configure(actor,family,org);confirm(confirmed);var row=store.get(org,id,true);revision(row,expected);scope(actor,family,org,row.principal());
        byte[] key=new byte[32];random.nextBytes(key);
        try{UUID ref=secrets.put(actor,family,org,"WEBHOOK_SIGNING",key);Instant now=clock.instant().truncatedTo(ChronoUnit.MICROS);store.change(row,ref,actor,now,false);audit.write(org,actor,IntegrationAudit.Action.WEBHOOK_ROTATED,id,expected+1,now);return new Revealed(view(store.get(org,id,false)),Base64.getUrlEncoder().withoutPadding().encodeToString(key));}
        finally{Arrays.fill(key,(byte)0);}
    }
    @Transactional(timeout=10)
    public Endpoint revoke(UUID actor,UUID family,UUID org,UUID id,long expected){configure(actor,family,org);var row=store.get(org,id,true);revision(row,expected);Instant now=clock.instant().truncatedTo(ChronoUnit.MICROS);store.change(row,row.secret(),actor,now,true);audit.write(org,actor,IntegrationAudit.Action.WEBHOOK_REVOKED,id,expected+1,now);return view(store.get(org,id,false));}
    private AutomationAuthority.Principal scope(UUID actor,UUID family,UUID org,UUID principal){
        var p=automations.get(actor,family,org,principal);p=automations.worker(org,principal,p.workflowId(),p.versionId(),p.definitionHash());
        if(!workflows.get(actor,family,org,p.workflowId()).workflow().status().equals("ACTIVE"))throw new DomainFailure(409,"WORKFLOW_ARCHIVED");
        var snapshot=workflows.version(actor,family,org,p.workflowId(),p.versionId());if(snapshot.version().schemaVersion()!=2||!snapshot.version().definitionHash().equals(p.definitionHash())||!java.util.stream.StreamSupport.stream(snapshot.definition().path("nodes").spliterator(),false).anyMatch(n->n.path("type").asString().equals("webhookTrigger")))throw new DomainFailure(422,"INCOMPATIBLE_AUTOMATION_WORKFLOW");
        for(var grant:p.grants()){
            if(grant.classification().equals("WRITE"))authority.write(actor,family,org,"capability.write");
            if(grant.integrationId()!=null){var connection=connections.get(actor,family,org,grant.integrationId());if(!connection.enabled())throw new DomainFailure(409,"INTEGRATION_DISABLED");if(connection.revision()!=grant.revision())throw new DomainFailure(409,"STALE_CONNECTION");}
        }
        return p;
    }
    private void configure(UUID actor,UUID family,UUID org){authority.write(actor,family,org,"integration.manage");authority.write(actor,family,org,"execution.run");}
    private void confirm(boolean value){if(!value)throw new DomainFailure(422,"WEBHOOK_CONFIRMATION_REQUIRED");}
    private void revision(WebhookStore.Row r,long expected){if(expected<0)throw new DomainFailure(400,"INVALID_REQUEST");if(r.revision()!=expected||expected==Long.MAX_VALUE||!r.status().equals("ACTIVE"))throw new DomainFailure(409,"WEBHOOK_CONFLICT");}
    private Endpoint view(WebhookStore.Row r){return new Endpoint(r.id(),r.name(),r.principal(),r.workflow(),r.version(),r.hash(),r.revision(),r.status(),r.creator(),r.created(),r.author(),r.updated());}
}
