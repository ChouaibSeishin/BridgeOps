package com.bridgeops.approvals.application;

import com.bridgeops.approvals.persistence.ApprovalStore;
import com.bridgeops.approvals.persistence.ApprovalStore.Request;
import com.bridgeops.approvals.domain.ApprovalState;
import com.bridgeops.audit.application.ApprovalAudit;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
class ApprovalLifecycle {
    private final ApprovalStore store;private final ApprovalAudit audit;
    ApprovalLifecycle(ApprovalStore store,ApprovalAudit audit){this.store=store;this.audit=audit;}
    @Transactional(propagation=Propagation.MANDATORY)
    public void finish(Request r,ApprovalState outcome,String error,UUID actor,Instant now){store.finalize(r,outcome,error,now);store.event(r,actor,"APPROVAL_"+outcome.name(),now);audit.write(r.organization(),actor,ApprovalAudit.Action.valueOf("APPROVAL_"+outcome.name()),r.id(),r.revision()+1,now);}
}
