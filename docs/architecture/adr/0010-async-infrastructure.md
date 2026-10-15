# ADR-0010-async-infrastructure: Conditional asynchronous infrastructure

Status: Reviewed design. Date: 2026-10-15. Implementation evidence remains separate.

## Context

The specification mentions Redis and RabbitMQ without making them prerequisites of early phases.

## Alternatives

Require both from day one; never introduce background processing.

## Decision

No broker/cache in Phase 1. Bounded durable database worker in revised Phase 3. Add RabbitMQ only for measured queue/isolation need with outbox and dedup; Redis only for distributed throttling/coordination.

## Consequences

Fewer initial failure modes. Database queue capacity must be measured; later broker introduction adds real operational responsibilities.
