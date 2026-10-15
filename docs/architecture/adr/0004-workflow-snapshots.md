# ADR-0004-workflow-snapshots: Immutable workflow snapshots

Status: Proposed. Date: 2026-10-15.

## Context

Draft changes must not alter in-flight execution.

## Alternatives

Executing the latest mutable JSON; copying graph state ad hoc on each step.

## Decision

Separate optimistic drafts from immutable published versions and immutable TEST snapshots. Canonical definition hash, schema/type versions and atomic publication.

## Consequences

Additional storage and migration discipline; old snapshots remain readable while referenced. Runtime grants and guards prevent snapshot mutation.
