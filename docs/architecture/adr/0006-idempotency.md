# ADR-0006-idempotency: Idempotency and external effect limits

Status: Accepted for Phase 3 implementation, 2026-10-17. Proposed: 2026-10-15. Implementation verification remains separate.

## Context

Retries and duplicate delivery can repeat business changes.

## Alternatives

Assume exactly once; blindly retry all failures.

## Decision

Canonical request hashes and scoped start keys; stable logical operation/item keys, internal atomic receipts and provider preconditions/idempotency where supported. Unknown remote outcomes require reconciliation.

## Consequences

At-least-once processing remains possible. Retention bounds dedup guarantees; provider limitations are visible rather than hidden.

Phase 3 implements the PostgreSQL worker and internal record receipts under [the execution contract](../../development/PHASE_3_EXECUTION.md). Phase 4 adds durable approval signals and unique consumption receipts under [the approval contract](../../development/PHASE_4_APPROVALS.md). External effects remain later work.
