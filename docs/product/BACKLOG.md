# Prioritized implementation backlog

Revised 2026-10-15 by the user's documentation review. This seven-phase roadmap supersedes the original 0–10 implementation numbering without dropping requirements. Architecture and public contracts remain intact.

| Priority / phase | Work | Required acceptance |
| --- | --- | --- |
| P0 / 1A | Locked toolchain, Boot/React/PostgreSQL, Flyway, health/info, shell, Compose and CI | Backend/frontend checks; required real PostgreSQL migration; actual container startup and frontend API request; safe probe outage/recovery |
| P0 / 1B | Authentication, cookies/CSRF, refresh rotation/reuse, revocation, rate limits, audit | Valid/invalid login, expired/wrong claims, hostile Origin/CSRF, one-use refresh concurrency, logout/reuse and brute-force tests |
| P0 / 1C | One-shot offline first account; account-level create-org grant; organizations, roles, membership/invites | Bootstrap disabled/repeat/concurrent tests; atomic Owner membership; recipient-bound invitation; last-owner and cross-tenant tests |
| P0 / 1D | Real login/organization UI, recovery/session settings, foundation dashboard | Browser onboarding/login/logout; truthful metrics; Phase 1 documented clean-checkout startup and security gates |
| P0 / 2 | Small node registry, graph schema, draft persistence, immutable publication and version references; simple form UI first | Save/reload; backend invalid-graph rejection; optimistic edit/publish race; tenant boundaries; published mutation rejected |
| P0 / 3 | Durable sequential execution, steps/attempts/events, PostgreSQL work queue, synthetic capability, retries/delay/deadline/cancel/idempotency/fencing | Repeated request, interrupted worker and stale lease tests; partial outcomes; 24h includes every wait; state/attempt history visible |
| P0 / 4 | One distinct eligible approver, policies, exact intent, waiting/resume, audit | Self/unauthorized/repeated/conflicting decisions; changed intent; expiry/deadline; cancelled execution cannot resume; first business vertical slice works end to end |
| P0 / 5 | Sample support/team/notification systems, controlled REST/secret references, webhooks/automation grants and richer editor | SSRF controls gate HTTP; signature/replay/dedup tests; safe connection failures; complete triage batch demo; real-effects test confirmation |
| P1 / 6 | Mock and real AI adapters, bounded authorized tools, structured plans, authenticated MCP | No-key deterministic demo; injection/permission/schema/provider failure tests; compatible MCP discovery/invocation; no approval bypass |
| P1 / 7 | Real analytics/audit views, responsive/accessibility polish, full seed/demo, delivery/cloud guide | Persisted-data metrics; actual screenshots; full critical path/browser tests; clean checkout; backup/restore and security-reporting release gate |

Mapping: original Phase 1 and 2 -> revised Phase 1; original 4 -> revised 2; original 5 -> revised 3; original approvals in 6 -> revised 4; original 3 and webhooks in 6 -> revised 5; original 7–8 -> revised 6; original 9–10 -> revised 7. A minimal deterministic synthetic capability moves into execution Phase 3 so execution tests need no external integration implementation.

P0 means first operational MVP, not optional security. AI/MCP remain required portfolio increments. RabbitMQ requires measured need plus outbox/dedup/outage tests. Redis requires a defined distributed rate-limit/coordination responsibility. Neither is required in the foundation.

Every increment includes validation, backend authorization, migrations, safe errors, meaningful tests, frontend states and synchronized docs. Source generation, skipped tests or a Compose file alone do not meet acceptance. Use the [Phase 1 sequence](../development/PHASE_1_IMPLEMENTATION.md) and [verification plan](../development/PHASE_1_VERIFICATION.md).

## Historical Phase 3 checkpoint

Phase 1D, Phase 2 and Phase 3 are complete. Phase 3 runs immutable published linear manual-trigger/internal-record/end chains through PostgreSQL durable work, fenced recovery, receipts, attempts, retries, deadlines and cancellation. Tenant-authorized APIs and persisted browser monitoring are verified. All 206 backend/integration/browser tests and 54 frontend tests passed, along with container/same-origin, fresh/V9 migration, preservation, dependency/secret scans and [corresponding implementation CI](https://github.com/ChouaibSeishin/BridgeOps/actions/runs/39070309725). [Evidence and limitations](../PROJECT_STATUS.md). Existing account, consumed marker, environment, keys, tenant, saved definitions and volume remain preserved.

## Historical Phase 4 checkpoint

Phase 4 is complete and verified: distinct-human decisions, bounded immutable policies, exact intent, central capability enforcement, durable waiting/signals/receipts, expiry/cancellation/recovery and authenticated inbox/detail/policy UI. All 265 backend/PostgreSQL/browser tests and 68 frontend tests passed with no failures/errors/skips. Fresh/V10 migrations, preserved-data V11 production upgrade, security smoke, production build, dependency/secret scans and [corresponding implementation CI](https://github.com/ChouaibSeishin/BridgeOps/actions/runs/39294922314) passed. Exact contracts/evidence are in the [approval guide](../development/PHASE_4_APPROVALS.md) and project status; preceding Phase 3 counts are historical.

## Phase 5 release verification and next priorities

The Phase 5 critical path is implemented: persisted support/team/internal-notification systems, encrypted secret references, registered allowlisted REST operations, graph-v2 branching/mappings/transforms/delays, exact approved internal/external writes, scoped automation actors, signed durable webhooks and typed visual authoring. The opt-in triage example and visibly authored triage/REST browser paths verify actual effects, receipts, independent approval, no-work routing and tenant denials. Publication/setup never executes work or enables automation.

Clean local verification passed 517 backend/PostgreSQL/browser tests (135 unit/MVC + 382 integration/browser), all 148 frontend tests, typecheck/lint/build, strict zero-skip report checks, dependency and secret scans. The preserved local V11 → V24 Compose upgrade passed with three healthy services and unchanged existing data/material/bootstrap/volume fingerprints. Exact hosted implementation CI passed, including Linux private-key/read-only/root-refusal checks; [current evidence](../PROJECT_STATUS.md) distinguishes completed gates from historical checkpoints. Phase 5 is complete within its documented product boundaries.

Next planned work is Phase 6: deterministic mock and real AI adapters, bounded authorized tools and structured plans, followed by authenticated interoperable MCP. Phase 7 retains analytics, accessibility/deployment polish and backup/restore verification. Neither is implemented by Phase 5. External email/SMS notifications, schedule triggers, arbitrary expressions/scripts, draft TEST snapshots and dry-run execution remain outside the delivered Phase 5 contract.
