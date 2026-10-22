package com.bridgeops.executions.application;

import com.bridgeops.approvals.application.OperationApprovalIntent;
import java.util.*;

/** Execution-owned adapter port. Every adapter must independently rederive the persisted fenced context. */
public interface CapabilityDispatch {
    record Work(UUID organization,UUID execution,UUID owner,long fence,Set<UUID> actors){
        public Work{if(organization==null||execution==null||owner==null||fence<1||actors==null||actors.isEmpty()||actors.size()>51)throw new IllegalArgumentException("Invalid work reference.");actors=Set.copyOf(actors);}
    }
    record Binding(UUID id,String capability,String operation,String classification,OperationApprovalIntent.Connection connection,String arguments,String argumentHash){
        @Override public String toString(){return "CapabilityBinding[REDACTED]";}
    }
    record Result(String output,boolean failed,String code,boolean reused){@Override public String toString(){return "CapabilityResult[REDACTED]";}}
    Binding prepare(Work work,String action);
    Binding resume(UUID organization,UUID execution,String action,Set<UUID> actors);
    Result perform(Work work,String action);
}
