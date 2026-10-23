package com.bridgeops.integrations.application;

import com.bridgeops.audit.application.IntegrationAudit;
import com.bridgeops.integrations.persistence.DemoWorkflowStore;
import com.bridgeops.organizations.application.TenantAuthorization;
import com.bridgeops.shared.web.DomainFailure;
import com.bridgeops.workflows.application.WorkflowService;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/** Explicit installation through public application boundaries; no account, principal, endpoint or signing key is created. */
@Service
public class DemoWorkflowService {
    public record Connection(UUID id,long revision){}
    public record Version(UUID workflowId,UUID versionId,String definitionHash){}
    public record Artifacts(Connection support,Connection teams,Connection notifications,Version manual,Version webhook,Instant createdAt,String provenance){}
    public record Result(boolean created,Artifacts artifacts){}
    private final DemoWorkflowStore store;private final TenantAuthorization authority;private final BusinessService business;
    private final ConnectionService connections;private final WorkflowService workflows;private final IntegrationAudit audit;private final Clock clock;
    public DemoWorkflowService(DemoWorkflowStore store,TenantAuthorization authority,BusinessService business,ConnectionService connections,WorkflowService workflows,IntegrationAudit audit,Clock clock){this.store=store;this.authority=authority;this.business=business;this.connections=connections;this.workflows=workflows;this.audit=audit;this.clock=clock;}
    @Transactional(readOnly=true,timeout=5)
    public Result get(UUID actor,UUID family,UUID org){authority.read(actor,family,org,"capability.read");authority.read(actor,family,org,"workflow.read");authority.read(actor,family,org,"integration.read");return new Result(false,store.read(org).map(this::view).orElse(null));}
    @Transactional(timeout=20)
    public Result install(UUID actor,UUID family,UUID org,boolean confirmed){
        for(String permission:List.of("integration.manage","capability.write","workflow.edit","workflow.publish"))authority.write(actor,family,org,permission);
        if(!confirmed)throw new DomainFailure(422,"DEMO_CONFIRMATION_REQUIRED");
        var previous=store.read(org);if(previous.isPresent())return new Result(false,view(previous.get()));
        business.demo(actor,family,org,true);
        var support=connection(actor,family,org,"Example triage support","SUPPORT");var teams=connection(actor,family,org,"Example triage teams","TEAMS");var notifications=connection(actor,family,org,"Example triage internal delivery","NOTIFICATIONS");
        var manual=publish(actor,family,org,false,support,teams,notifications);var webhook=publish(actor,family,org,true,support,teams,notifications);
        var manifest=new DemoWorkflowStore.Manifest(support,teams,notifications,manual,webhook,clock.instant(),"SEEDED_EXAMPLE");store.insert(org,actor,manifest);audit.write(org,actor,IntegrationAudit.Action.DEMO_WORKFLOWS_INSTALLED,org,0,clock.instant());return new Result(true,view(store.read(org).orElseThrow()));
    }
    private DemoWorkflowStore.Connection connection(UUID actor,UUID family,UUID org,String name,String type){var connection=connections.create(actor,family,org,name,"Dedicated opt-in internal example connection.",type,JsonMapper.builder().build().createObjectNode(),null).connection();return new DemoWorkflowStore.Connection(connection.id(),connection.revision());}
    private DemoWorkflowStore.Version publish(UUID actor,UUID family,UUID org,boolean webhook,DemoWorkflowStore.Connection support,DemoWorkflowStore.Connection teams,DemoWorkflowStore.Connection notifications){
        var definition=DemoWorkflowTemplates.definition(webhook,support.id(),support.revision(),teams.id(),teams.revision(),notifications.id(),notifications.revision());var draft=workflows.create(actor,family,org,webhook?"Example webhook ticket triage":"Example manual ticket triage","Opt-in example of internal ticket/team operations, exact human approval and persisted internal delivery. No external service or email is contacted.",definition);
        var version=workflows.publish(actor,family,org,draft.workflow().id(),draft.workflow().revision()).version();return new DemoWorkflowStore.Version(draft.workflow().id(),version.id(),version.definitionHash());
    }
    private Artifacts view(DemoWorkflowStore.Manifest row){return new Artifacts(connection(row.support()),connection(row.teams()),connection(row.notifications()),version(row.manual()),version(row.webhook()),row.createdAt(),row.provenance());}
    private Connection connection(DemoWorkflowStore.Connection value){return new Connection(value.id(),value.revision());}
    private Version version(DemoWorkflowStore.Version value){return new Version(value.workflowId(),value.versionId(),value.definitionHash());}
}
