# Current review disposition — 2026-10-15

The user has reviewed Phase 0 and authorized conflict resolution plus Phase 1A. The [conflict report](CONFLICT_RESOLUTION.md) resolves the historic pending items below; the [seven-phase backlog](product/BACKLOG.md) supersedes old numbering. Boot/Jupiter choice is now explicitly amended. This file retains the original review rationale as historical context, not a current approval block.

---

# Phase 0 review package

Historical Phase 0 rationale, 2026-10-15 UTC. The subsequent documentation review is complete and authorizes incremental implementation. Pending wording in the original table below records prior alternatives, now resolved in CONFLICT_RESOLUTION.md.

Objective: define a coherent, secure and finishable product before implementing the foundation. Dependencies: an empty Git repository and the supplied master prompt. Files added: product requirements/backlog, architecture/runtime/database designs, security model, API outline, version-selection and verification plans, ADRs, design tokens, repository placeholders and a documentation checker. See the [README index](../README.md).

## Specification tensions and proposed resolutions

| Tension or missing decision | Proposed resolution | Implication |
| --- | --- | --- |
| Phase 0 requests design only; the general protocol asks for runnable software at every milestone | Phase 0 is a reviewed design artifact; runnable software starts in Phase 1 | No misleading placeholder application |
| Acyclic sequential graphs versus “for each ticket” in the flagship demo | A bounded typed batch capability handles up to 50 ticket items with a persistent operation ledger; no general editor loops or parallel fan-out | Per-item results and safe retry without creating a programming language |
| Generic REST versus controlled connectors and SSRF prevention | Organization builders choose an administrator-configured destination and operation template; no unrestricted URL input | Private demo capabilities are in-process calls, outside generic HTTP |
| Platform administrator versus tenant privacy | Platform permissions never imply organization membership; exceptional access stays out of the MVP | No implicit superuser tenant access |
| Approval node versus API/AI/MCP writes | All sensitive capability writes pass the same policy service even outside a workflow; the graph node is a visible pause point | Removing a graph node cannot remove required approval |
| Immutable published versions versus editable drafts | Separate mutable draft table and append-only published snapshots | Optimistic draft editing and stable in-flight execution |
| AI plan “approve” endpoint versus an existing approvals queue | Plan submission creates a normal execution and approval; do not add a second approval authority | One concurrency-safe decision path |
| Immediate session revocation versus self-contained access JWTs | JWT includes session-family ID; each request checks live session/account state in PostgreSQL | Revocation is immediate at a database-read cost |
| Stable current Spring Boot versus explicit JUnit 5 | Current Boot documentation uses JUnit 6; recommend Boot 4.1 candidate with managed Jupiter 6 as a visible specification amendment | Pending review; do not silently override JUnit or choose an old release just to preserve a number |
| Full product versus a small MVP | First operational MVP ends at Phase 6; AI and MCP are required portfolio increments in Phases 7–8 | Neither is dropped from final scope |
| “No hardcoded credentials” versus documented demo accounts | Opt-in seed accepts local password input; development account identities may be documented, passwords never ship in production initialization | No default production login |
| Async introduced in Phase 5 versus earliest runnable foundation | Phase 1 has no worker or broker; Phase 5 uses a PostgreSQL-backed work queue first | RabbitMQ requires an observed need and an ADR update |
| Webhook background execution has no logged-in browser user | Bind each endpoint to a restricted organization automation principal and immutable published version | Verify grants at execution; never inherit unrestricted owner power |
| Static seeded histories versus real operational metrics | Label historical fixtures by provenance; default dashboard excludes them | Demonstration metrics describe actual runs |

## Assumptions for review

Single-region local/portfolio deployment; shared database with explicit tenant keys; no SSO, billing, arbitrary code or parallel graph execution in the MVP. Organizations use fixed role templates with permission checks, not a custom policy language. User enrollment is invite-first; the initial owner is provisioned using an explicit local bootstrap command. Delivery email uses a development outbox viewer; real email requires a later configured adapter. Authentication is still real; the UI must label delivery as local demo delivery.

The first workflow reads at most 50 matching tickets in a stable snapshot, branches on an empty result, prepares exact assignments, waits for a different eligible human, applies assignments with per-item optimistic checks, and records an internal notification. Work beyond the limit requires another explicit run. There is no promise of atomic changes across unrelated external APIs.

## Review checkpoint

The highest-impact review items are the bounded batch action, the shared-database tenancy model, the two-person approval rule, and the proposed Boot/JUnit amendment. All ADRs remain proposed. A disagreement updates these documents before implementation. No infrastructure installation, service exposure, deployment or Phase 1 application code is included here.

## Completion evidence

The twelve requested design deliverables are linked in the README. Documentation structure and internal links can be checked with `node scripts/verify-docs.mjs`; actual outcomes are recorded in [project status](PROJECT_STATUS.md). Mermaid diagrams are source proposals, not execution evidence. Phase 0 deliverables are ready for review; review acceptance remains pending.
