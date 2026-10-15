# ADR-0012-bounded-triage: Bounded batch capability for triage

Status: Proposed. Date: 2026-10-15.

## Context

The flagship processes each ticket while the MVP graph rejects loops.

## Alternatives

General loop/fan-out language; pretend the batch is one opaque successful action.

## Decision

Typed bounded batch action with at most 50 persisted item intents, preconditions and receipts, sequential application and visible partial failure.

## Consequences

No generic looping or unbounded automation. A changed item stops dispatch and requires a revised approved plan; item-level recovery remains explicit.
