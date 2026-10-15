# ADR-0001-modular-monolith: Modular monolith

Status: Proposed. Date: 2026-10-15.

## Context

The domain and failure boundaries are still being established.

## Alternatives

Microservices; a single unstructured package.

## Decision

One deployable Spring application with module-owned persistence and public contracts. Enforce boundaries through architecture tests when source exists.

## Consequences

Local transactions simplify correctness. Independent scaling is limited; extraction requires stable contracts and operational justification.
