package com.bridgeops.executions.domain;

import java.util.Set;

/** Explicit guards are shared by submission, dispatch, recovery and cancellation. */
public enum ExecutionState {
    QUEUED, RUNNING, WAITING_FOR_RETRY, WAITING_FOR_DELAY, SUCCEEDED, FAILED, CANCELLED;

    public boolean terminal() { return this == SUCCEEDED || this == FAILED || this == CANCELLED; }

    public boolean canTransitionTo(ExecutionState next) {
        if (terminal() || next == this) return false;
        if (next == FAILED || next == CANCELLED) return true;
        return switch (this) {
            case QUEUED -> next == RUNNING;
            case RUNNING -> Set.of(WAITING_FOR_RETRY, WAITING_FOR_DELAY, SUCCEEDED).contains(next);
            case WAITING_FOR_RETRY, WAITING_FOR_DELAY -> next == QUEUED;
            default -> false;
        };
    }

    public void requireTransition(ExecutionState next) {
        if (!canTransitionTo(next)) throw new IllegalStateException("Invalid execution transition.");
    }
}
