# ADR-0006-idempotency: Idempotency and external effect limits

Status: Proposed. Date: 2026-10-15.

## Context

Retries and duplicate delivery can repeat business changes.

## Alternatives

Assume exactly once; blindly retry all failures.

## Decision

Canonical request hashes and scoped start keys; stable logical operation/item keys, internal atomic receipts and provider preconditions/idempotency where supported. Unknown remote outcomes require reconciliation.

## Consequences

At-least-once processing remains possible. Retention bounds dedup guarantees; provider limitations are visible rather than hidden.
