# ADR-0005-durable-execution: Durable scheduling and recovery

Status: Proposed. Date: 2026-10-15.

## Context

Work must survive process crashes and approval waits without long transactions.

## Alternatives

In-memory executor only; broker-first design; external workflow framework.

## Decision

Persist explicit states, work leases/fencing, due retries/delays, attempts and approval signals in PostgreSQL. Reconcile startup and periodically.

## Consequences

Recovery is testable in one datastore. External ambiguous effects still need receipts/reconciliation; PostgreSQL becomes a critical dependency.
