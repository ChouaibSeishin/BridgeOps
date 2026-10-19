package com.bridgeops.executions.application;

import com.bridgeops.executions.domain.ExecutionState;
import java.time.Instant;
import java.util.*;

public final class ExecutionViews {
    private ExecutionViews() {}
    public record Summary(UUID id, UUID workflowId, UUID versionId, String definitionHash, UUID requestedBy,
        String source, String mode, ExecutionState status, long revision, int currentPosition,
        Instant createdAt, Instant startedAt, Instant completedAt, Instant deadlineAt, Instant notBefore,
        String errorCode, UUID retryOfExecutionId, boolean retryEligible,UUID approvalId,Instant approvalExpiresAt) {}
    public record Step(String nodeId, int position, String type, String status, int attemptCount,
        Instant startedAt, Instant completedAt, Object output, String errorCode, Instant retryAt) {}
    public record Attempt(UUID id, String nodeId, int attemptNumber, Instant startedAt, Instant completedAt, String outcome, String errorCode) {}
    public record Event(long sequence, Instant occurredAt, String code, String nodeId) {}
    public record Page<T>(List<T> items, int page, int size, long totalElements, long totalPages) {}
    public record Submission(Summary execution, boolean replay) {}
}
