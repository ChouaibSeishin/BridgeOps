# ADR-0015: Scoped automation and separation of human decisions

Status: Accepted design, 2026-10-21. Implementation/verification remains incremental in the Phase 5 guide.

## Context

V10/V11 executions and approvals reference real users. A signed webhook cannot borrow its configurator's session or invent a user. Integration management alone must not create execution authority. Existing self-approval protection must remain meaningful when a requester is automated.

## Decision

Organizations owns durable automation identity and immutable operation grants. Integrations orchestrates creation from an authorized, exact immutable schema-v2 webhook-triggered version and current registered connections. A principal pins one version/hash; grants enumerate node, connection/revision where applicable, operation and READ/WRITE classification. No wildcard, role editor, bearer login or public direct-run endpoint. New configurations use a new principal; revocation is terminal. Creation/revocation requires both integration.manage and execution.run, plus capability.write when creating a write grant. Membership/session checks apply to the configuring human. Worker authority comes from the live principal, organization and exact grant, not the creator's current session. Connection enablement/revision and operation policy are rechecked separately at dispatch/effect.

Execution/audit/approval actor evolution is additive: USER and AUTOMATION have explicit, tenant-qualified references with exactly-one constraints. Existing user IDs/definitions/policies/hashes/receipts are retained. No endpoint accepts webhook execution until its actor-aware runtime and exact-intent approval path are available. Graph publication and principal configuration do not execute work.

Schema-v2 approval intent additionally binds the automation principal and its configuring human, plus the webhook endpoint's configuring human when different. Those configuring humans are ineligible to decide that execution's protected approval. A distinct eligible human must approve. This conservative rule prevents configuring an automation from becoming a way to approve one's own proposed changes. USER executions continue to exclude the actual requester. Automation never decides approvals; immutable decisions always name a real current eligible human. Revocation and policy/precondition changes stop future effects while preserving completed history.

## Consequences

Builder's integration.manage grant alone cannot manufacture execution.run; Operator cannot configure connections merely because it can run workflows. An installation with only one eligible human cannot demonstrate an approval-sensitive automation until a different eligible member is available. Creator identities remain historical separation-of-duties inputs, not substituted execution requesters. Exact version/connection changes require explicit new authority rather than silent scope expansion. PostgreSQL tenant-qualified foreign keys, immutable grants, transactional audit and live application checks enforce the boundary.
