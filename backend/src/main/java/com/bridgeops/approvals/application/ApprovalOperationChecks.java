package com.bridgeops.approvals.application;

import java.util.UUID;

/** Approval-owned port for current non-synthetic operation preconditions, never an effect dispatcher. */
public interface ApprovalOperationChecks {
    void validateDecision(UUID actor,UUID family,UUID organization,UUID execution,String action,UUID preparedIntent);
}
