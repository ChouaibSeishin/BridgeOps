package com.bridgeops.approvals.domain;

/** A request has exactly one final disposition; decisions cannot revise history. */
public enum ApprovalState {
    PENDING, APPROVED, REJECTED, EXPIRED, INVALIDATED, CANCELLED;

    public boolean terminal() { return this != PENDING; }
    public boolean canTransitionTo(ApprovalState next) { return this == PENDING && next != null && next != PENDING; }
    public void requireTransition(ApprovalState next) {
        if (!canTransitionTo(next)) throw new IllegalStateException("Invalid approval transition.");
    }
}
