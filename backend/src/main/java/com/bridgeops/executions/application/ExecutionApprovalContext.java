package com.bridgeops.executions.application;

import com.bridgeops.approvals.application.ApprovalExecutionContext;
import com.bridgeops.executions.persistence.ExecutionStore;
import com.bridgeops.organizations.application.TenantAuthorization;
import com.bridgeops.workflows.application.WorkflowService;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** Implements approval's context port without exposing an execution repository/entity. */
@Service
public class ExecutionApprovalContext implements ApprovalExecutionContext {
    private final ExecutionStore store;private final TenantAuthorization authority;private final WorkflowService workflows;
    public ExecutionApprovalContext(ExecutionStore store,TenantAuthorization authority,WorkflowService workflows){this.store=store;this.authority=authority;this.workflows=workflows;}
    @Transactional(readOnly=true,timeout=5)
    public View read(UUID actor,UUID family,UUID org,UUID execution){authority.read(actor,family,org,"approval.read");return view(actor,family,org,execution,false);}
    @Transactional(propagation=Propagation.MANDATORY)
    public View lock(UUID actor,UUID family,UUID org,UUID execution){authority.write(actor,family,org,"approval.decide");var result=view(actor,family,org,execution,true);store.jdbc().queryForList("SELECT execution_id FROM execution_work_items WHERE organization_id=? AND execution_id=? FOR UPDATE",org,execution);return result;}
    private View view(UUID actor,UUID family,UUID org,UUID id,boolean lock){var e=store.get(org,id,lock);var source=workflows.reference(actor,family,org,e.workflow(),e.version());var inputs=source.schemaVersion()==2?new com.bridgeops.executions.persistence.OperationInputStore(store.jdbc()).readAll(org,id):java.util.Map.<String,com.bridgeops.executions.persistence.OperationInputStore.Prepared>of();return new View(org,id,e.workflow(),e.version(),e.hash(),e.actor(),e.deadline(),e.state().name(),e.revision(),e.position(),store.steps(org,id).stream().map(s->{
        var p=inputs.get(s.node());OperationData prepared=p==null?null:new OperationData(p.id(),p.arguments(),p.argumentHash(),p.capability(),p.operation(),p.classification(),p.connection());
        return new Action(s.node(),s.position(),s.type(),s.operation(),s.argumentHash(),s.state(),prepared);
    }).toList(),source.name(),source.versionNumber());}
}
