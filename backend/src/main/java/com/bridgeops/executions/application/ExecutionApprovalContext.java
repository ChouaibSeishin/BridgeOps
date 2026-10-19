package com.bridgeops.executions.application;

import com.bridgeops.approvals.application.ApprovalExecutionContext;
import com.bridgeops.executions.persistence.ExecutionStore;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** Implements approval's context port without exposing an execution repository/entity. */
@Service
public class ExecutionApprovalContext implements ApprovalExecutionContext {
    private final ExecutionStore store;
    public ExecutionApprovalContext(ExecutionStore store){this.store=store;}
    public View read(UUID org,UUID execution){return view(org,execution,false);}
    @Transactional(propagation=Propagation.MANDATORY)
    public View lock(UUID org,UUID execution){var result=view(org,execution,true);store.jdbc().queryForList("SELECT execution_id FROM execution_work_items WHERE organization_id=? AND execution_id=? FOR UPDATE",org,execution);return result;}
    private View view(UUID org,UUID id,boolean lock){var e=store.get(org,id,lock);return new View(org,id,e.workflow(),e.version(),e.hash(),e.actor(),e.deadline(),e.state().name(),e.revision(),e.position(),store.steps(org,id).stream().map(s->new Action(s.node(),s.position(),s.type(),s.operation(),s.argumentHash(),s.state())).toList());}
}
