package com.bridgeops.executions.application;

import java.util.UUID;
import org.springframework.stereotype.Component;

/** Controlled capability boundary. No workflow-supplied code, URLs or failure switches. */
@Component
public class SyntheticCapability {
    public record Invocation(UUID organization,UUID actor,UUID operation,String node,String message) {}
    public enum FailureKind { TRANSIENT, PERMANENT, AMBIGUOUS }
    public static final class Failure extends RuntimeException {
        private final FailureKind kind;
        public Failure(FailureKind kind){super("Synthetic capability did not complete.");this.kind=kind;}
        public FailureKind kind(){return kind;}
    }
    /** Bounded preparation outside the transaction. The internal effect is committed by the fenced runtime. */
    public void prepare(Invocation invocation) {}
    /** Test subclasses may interrupt here to verify recovery after the atomic effect commit. */
    public void afterCommit(Invocation invocation) {}
}
