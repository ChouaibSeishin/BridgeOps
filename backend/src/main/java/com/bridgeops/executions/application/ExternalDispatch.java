package com.bridgeops.executions.application;

import java.time.Duration;

/** Network work occurs outside transactions; only a revalidated, fenced finish may commit its observation. */
public interface ExternalDispatch {
    interface Observation {}
    record Completion(String output,SyntheticCapability.FailureKind failure,String code,boolean reused,Duration retryAfter){
        @Override public String toString(){return "ExternalCompletion[REDACTED]";}
    }
    Observation execute(CapabilityDispatch.Work work,String action);
    Completion finish(CapabilityDispatch.Work work,String action,Observation observation);
}
