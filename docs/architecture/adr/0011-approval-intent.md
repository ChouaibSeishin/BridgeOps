# ADR-0011-approval-intent: Approval binds exact intent

Status: Reviewed design, clarified after documentation review. Date: 2026-10-15.

## Context

An approved summary must not authorize changed arguments or alternate entry points.

## Alternatives

Graph-only approval node; separate AI approval authority; requester self-approval; a two-approver quorum.

## Decision

One policy gate across workflow/AI/MCP. Bind canonical operation hash and preconditions. Exactly one different eligible human approves or rejects: requester plus decision-maker, not two separate approvers. Backend rechecks identity, grants, policy and expiry at dispatch and atomically guards the single final decision and resumption.

## Consequences

Single-owner organizations must invite an approver. Changed data requires new approval; partial external batches cannot be rolled back by a local transaction. Unique approval_request_id on decisions matches this policy. Hidden frontend buttons alone never enforce authorization.
