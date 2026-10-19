package com.bridgeops.approvals.application;

import java.time.Instant;
import java.util.*;

/** Owned port: execution supplies safe context and its locks, never its persistence objects. */
public interface ApprovalExecutionContext {
    record Action(String node,int position,String type,UUID operation,String argumentHash,String status) {}
    record View(UUID organization,UUID execution,UUID workflow,UUID version,String definitionHash,UUID requester,
        Instant deadline,String status,long revision,int position,List<Action> steps) {
        public boolean terminal(){return Set.of("SUCCEEDED","FAILED","CANCELLED").contains(status);}
        public Action action(String node){return steps.stream().filter(s->s.node().equals(node)).findFirst().orElseThrow();}
    }
    View read(UUID organization,UUID execution);
    /** Caller holds its actor and organization locks; implementation locks execution then work. */
    View lock(UUID organization,UUID execution);
}
