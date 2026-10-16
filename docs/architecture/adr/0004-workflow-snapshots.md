# ADR-0004-workflow-snapshots: Immutable workflow snapshots

Status: Accepted. Proposed: 2026-10-15. Accepted: 2026-10-16 for Phase 2 implementation.

## Context

Draft changes must not alter in-flight execution.

## Alternatives

Executing the latest mutable JSON; copying graph state ad hoc on each step.

## Decision

Separate optimistic drafts from immutable published versions. Publication validates the persisted expected revision, canonicalizes schema/type-versioned definition JSON, hashes it with SHA-256 and atomically writes the version, current pointer and safe audit. Same-revision publication is single-use and concurrent requests conflict. Composite tenant/workflow keys and runtime grants plus immutable-row guards enforce snapshot identity and immutability. See the [Phase 2 contract](../../development/PHASE_2_WORKFLOWS.md).

Immutable TEST snapshots remain accepted future execution design, intentionally not implemented in Phase 2. No execution, test-run or approval processing endpoint is enabled by definition publication.

## Consequences

Additional storage and migration discipline; old snapshots remain readable while referenced. Runtime grants and guards prevent snapshot mutation.
