package com.bridgeops.integrations.application;

import com.bridgeops.executions.application.ExecutionAutomation;
import com.bridgeops.integrations.persistence.WebhookStore;
import com.bridgeops.organizations.application.AutomationAuthority;
import com.bridgeops.shared.domain.AutomationOrigin;
import com.bridgeops.shared.web.DomainFailure;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** Live automation/endpoint authority, independent of any configuring human's session or roles. */
@Service
public class AutomationExecutionGateway implements ExecutionAutomation {
    private final AutomationAuthority automations;private final WebhookStore endpoints;
    public AutomationExecutionGateway(AutomationAuthority automations,WebhookStore endpoints){this.automations=automations;this.endpoints=endpoints;}
    @Override @Transactional(propagation=Propagation.MANDATORY,noRollbackFor=DomainFailure.class)
    public void authorize(UUID org,AutomationOrigin origin,UUID workflow,UUID version,String hash){
        var p=automations.worker(org,origin.principal(),workflow,version,hash);var endpoint=endpoints.get(org,origin.endpoint(),true);
        if(!endpoint.status().equals("ACTIVE"))throw new DomainFailure(403,"WEBHOOK_REVOKED");
        var accepted=endpoints.accepted(org,origin.endpoint(),origin.endpointRevision());
        if(!accepted.status().equals("ACTIVE")||!p.createdBy().equals(origin.principalCreator())||!accepted.principal().equals(origin.principal())||!accepted.principalCreator().equals(origin.principalCreator())||!accepted.author().equals(origin.endpointAuthor())
            ||!accepted.workflow().equals(workflow)||!accepted.version().equals(version)||!accepted.hash().equals(hash)||!endpoint.principal().equals(origin.principal()))throw new DomainFailure(403,"AUTOMATION_SCOPE_DENIED");
    }
    @Override @Transactional(propagation=Propagation.MANDATORY,noRollbackFor=DomainFailure.class)
    public void operation(UUID org,AutomationOrigin origin,UUID workflow,UUID version,String hash,String node,UUID connection,Long revision,String operation,String classification){
        authorize(org,origin,workflow,version,hash);automations.operation(org,origin.principal(),workflow,version,hash,new AutomationAuthority.Grant(node,connection,revision,operation,classification));
    }
}
