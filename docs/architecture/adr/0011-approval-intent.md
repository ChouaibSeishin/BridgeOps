# ADR-0011-approval-intent: Approval binds exact intent

Status: Accepted for Phase 4 implementation, 2026-10-19. Reviewed design: 2026-10-15. Verification is recorded separately.

## Context

An approved summary must not authorize changed arguments or alternate entry points.

## Alternatives

Graph-only approval node; separate AI approval authority; requester self-approval; a two-approver quorum.

## Decision

One policy gate across workflow/AI/MCP. Bind canonical operation hash and preconditions. Exactly one different eligible human approves or rejects: requester plus decision-maker, not two separate approvers. Backend rechecks identity, grants, policy and expiry at dispatch and atomically guards the single final decision and resumption.

## Consequences

Single-owner organizations must invite an approver. Changed data requires new approval; partial external batches cannot be rolled back by a local transaction. Unique approval_request_id on decisions matches this policy. Hidden frontend buttons alone never enforce authorization.

The [Phase 4 contract](../../development/PHASE_4_APPROVALS.md) fixes the schema-v1 next-record interpretation, low-risk default and bounded organization policy, exact canonical intent, signal/receipt boundary and actor-set lock ordering. Existing role grants are sufficient. A final historical decision is never rewritten to authorize a newer policy or changed operation.
