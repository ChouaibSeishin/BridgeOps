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

Phase 2 implementation uses separate V9 workflow/draft/version tables, tenant-qualified current-pointer foreign keys, immutable-row guards and denied runtime version mutation. Publication consumes the draft revision and records the canonicalization version plus checked SHA-256. Existing graph/metadata snapshots remain unchanged when drafts are saved, republished or archived. TEST snapshots and execution references remain Phase 3 additions, not current endpoints. Actual tests and publication evidence are maintained in the workflow guide and PROJECT_STATUS.md.
