# Product requirements

Status: active requirements, amended 2026-10-15 by the documentation review. The revised seven-phase roadmap in BACKLOG.md supersedes the original phase numbering. The [master prompt](MASTER_PROJECT_PROMPT.md) remains authoritative; [review resolutions](../PHASE_0_REVIEW.md) identify proposed interpretations and amendments.

## Product and users

BridgeOps lets an organization connect approved business capabilities, publish visual workflows, execute them durably, and investigate or propose actions through authorized AI tools. Its differentiator is a traceable route from an operational question to an approved business change.

Owners govern membership and policies. Administrators manage organization operations. Builders configure connections and publish workflows. Operators run and recover work. Approvers decide sensitive requests. Viewers inspect permitted data. Platform administrators manage platform configuration without automatic tenant access. Every role maps to explicit permissions; see the [permission matrix](../security/THREAT_MODEL.md).

## Required outcomes and acceptance

| ID | Requirement | Observable acceptance |
| --- | --- | --- |
| R01 | Authenticate and manage sessions | Invalid login fails; rotation/reuse/revocation and CSRF tests pass |
| R02 | Organization isolation and invitation | User joins through an expiring one-use invitation; foreign tenant IDs never reveal resources |
| R03 | Approved integrations | Support, team directory and notification capabilities work with synthetic data; secrets never appear in ordinary responses |
| R04 | Versioned workflow definitions | Save/reload the same graph; backend rejects invalid publication; immutable published graph/metadata survives draft edits. Runtime version pinning starts in Phase 3 |
| R05 | Persistent execution | Run, inspect steps, restart, retry eligible failures and cancel without losing durable state |
| R06 | Sensitive-action approval | Different eligible actor decides exact proposed changes; duplicate decisions cannot resume twice |
| R07 | Webhook automation | Authentic timestamped delivery produces one execution; invalid signatures/replays fail |
| R08 | AI assistant | Labeled deterministic mock works without credentials; real adapter handles structured calls; each call reauthorizes |
| R09 | MCP tools | Compatible client discovers permitted tools; unauthorized calls fail; writes use the same approval services |
| R10 | Operational visibility | Metrics derive from real executions; safe timeline, audit trail and connection health show failures clearly |
| R11 | Reproducibility | Clean checkout builds/tests/starts using documented commands, migrations and opt-in synthetic seed |
| R12 | Usable interface | Keyboard access, responsive navigation, light/dark tokens and loading/error/empty states cover the critical path |

## Precise first operational MVP: Phases 1–5

Include real invite-first authentication; organization creation/membership with all fixed roles; isolated PostgreSQL data; three sample in-process capabilities; configured allowlisted REST operations; one authenticated webhook type; immutable DAG workflows; sequential execution and conditions; persisted retry/delay; bounded transforms; approval queue; cancellation and supported recovery; basic overview/audit views; deterministic failure injection in the test/demo profile; Docker Compose; CI and meaningful backend/frontend tests.

Node catalog: manual/webhook trigger, internal action, configured HTTP request, condition, approval, delay, constrained transform, notification and end. No schedule trigger until a scheduling milestone. Transform operations are field selection/rename, literal fields and typed template interpolation; no scripts or unrestricted expressions. Conditions compare typed fields using allowlisted operators. Initial limits: 50 nodes, 100 edges, 50 batch items, 256 KiB trigger body, 1 MiB HTTP response, 10-second default external call timeout, three total attempts and 24-hour absolute execution deadline measured from execution creation, including queued time, retries, scheduled delays and human approval waits. Policy can impose tighter limits. These are design defaults to validate experimentally.

The visual editor supports node/edge add/delete, config, validation markers, draft save, duplication, publication, history and test runs. Test runs require execution permissions and normal approvals; they are real operations against designated demo connections, not an implied safe dry run. Basic accessible layout and theme support arrive early; richer analytics and complete responsive polish follow in Phase 7.

## Current Phase 2 scope amendment

Phase 1D and Phase 2 workflow definitions are implemented and verified; see the [Phase 2 contract](../development/PHASE_2_WORKFLOWS.md). A small form editor supports bounded versioned manual-trigger/synthetic-action/approval/end definitions, persistence, authoritative validation, immutable publication/history, duplication and archive. Phase 3 adds published-version runtime support for manualTrigger/syntheticAction-record/end only, with durable state/attempts/receipts, retries/scheduled start/deadline/cancel and monitoring. Approval-containing versions reject before execution creation. Draft TEST snapshots and graph delay nodes remain deferred; an explicit bounded start delay persists scheduling without changing schema v1. Approval processing begins in Phase 4. The broader catalog/canvas requirements above remain later targets. Existing local accounts/data must be preserved; use isolated synthetic test fixtures.

## Flagship demonstration

High-Priority Ticket Triage: manually start or authenticate a webhook; read up to 50 unresolved high-priority tickets; resolve responsible teams; branch to a no-work result when empty; build deterministic structured assignments; request approval; apply only unchanged approved assignments; notify the operations manager; inspect timeline and audit. A simulated transient notification error retries once, then succeeds. A changed ticket causes a visible stale-plan conflict and requires a new plan/approval. Partial application is visible; successful items are not replayed.

Completion requires a Playwright journey plus database assertions proving that the assignment occurred only after approval, retry did not duplicate the notification, and cross-tenant attempts failed. Seeded examples carry `provenance=SEEDED_EXAMPLE`; actual runs use `LIVE_EXECUTION`.

## Portfolio increments and deferred features

Phase 6 adds provider-independent AI, a real adapter, mock mode and authenticated MCP. Phase 7 finishes analytics, accessibility, UI quality, the clean-checkout demo, deployment guide and actual screenshots. These are required final deliverables, not optional MVP shortcuts.

Deferred beyond the initial release: schedules, general loops, parallel fan-out/join, arbitrary user code/SQL, plugin marketplace, custom role editor, SSO/SCIM, billing, Kubernetes, multi-region HA, compensation/saga DSL, automatic superuser access, many external SaaS adapters and tamper-evident cryptographic audit storage. Redis and RabbitMQ are conditional infrastructure, not mandatory feature badges.

## Nonfunctional commitments

Default deny; service-level authorization; short database transactions; database-enforced tenant relationships; append-only audit through runtime privileges; safe errors with correlation IDs; UTC timestamps; paginated collections; bounded external calls; no exactly-once guarantee across arbitrary APIs. Performance targets will be set with Phase 3 measurements, not invented throughput claims. Budget/concurrency defaults are per organization; tenant saturation must not starve others.

## Clarified onboarding and approval cardinality

Bootstrap creates one initial account, not an organization or public registration path. The account-level organization.create grant permits its first authenticated organization creation; that transaction also creates its Owner membership. Invitees receive only authorized tenant grants. The offline bootstrap is disabled by default and permanently refuses after its installation marker is consumed. See ADR 0014 for protection and tests.

One different eligible user may approve or reject an action. Requester plus decision-maker is the two-person rule; two separate approvers are not required. Backend policy applies to every entry point.

Test-run UI says **Run test — real effects**, lists destinations/actions and requires explicit confirmation. API effectsAcknowledged=true is required; no dry-run guarantee is implied.
