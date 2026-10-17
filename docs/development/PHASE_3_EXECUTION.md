# Phase 3 — durable sequential execution

Status: implementation in progress, starting from verified clean master b0bce52797765e475b7c4a07481802b928eb6304 on 2026-10-17. Prior Phase 2 test counts and local data counts are historical. No Phase 3 completion is claimed before final regression, preserved-data upgrade and corresponding hosted CI succeed.

## Contract and scope decisions

Schema v1 and canonicalization v1 remain unchanged. Runtime supports manualTrigger, syntheticAction/record and end, ordered by graph edges. Any approval node returns 422 UNSUPPORTED_RUNTIME_NODE before execution creation. No approvals, external connectors, arbitrary code, AI or MCP. A record is a bounded internal effect with an atomic receipt; it is not a notification or external business operation.

The existing graph has no trigger-input schema: input must be exactly `{}`. Published execution start pins an explicitly supplied versionId (or the current published version if omitted). It requires Idempotency-Key, scope `(organization,workflow,MANUAL,key)`. Fingerprints include exact version ID/hash, canonical input and scheduling options; matching replay returns the original resource (202 active, 200 terminal), mismatch 409. Start, queue and audit commit atomically. Archived/unpublished definitions cannot start.

The broader execution document describes future graph delay nodes and TEST snapshots. Phase 3 implements durable scheduling with optional start delaySeconds (integer 0–3600), not a new graph node or schema-v1 reinterpretation. First dispatch enters RUNNING, persists WAITING_FOR_DELAY and due work if its not-before time has not arrived. This exercises restart-safe scheduled waits without changing published hashes. Draft TEST snapshot routes remain deferred; this phase starts published versions only. The API outline must distinguish these implemented and planned routes.

States: QUEUED → RUNNING; RUNNING → WAITING_FOR_RETRY / WAITING_FOR_DELAY / SUCCEEDED / FAILED / CANCELLED; due waits → QUEUED; every active state may fail at its absolute deadline or cancel. Terminal states never resume. Default deadline is creation +24h, including all waits. Manual retry creates a new linked execution and explicit new budget, pins the same snapshot and reuses the original logical operation lineage/receipts; original execution remains terminal. Only exhausted eligible transient failure is manually retryable. Permanent, authorization, deadline, ambiguous and unsupported errors are not.

Attempts are append-only outcome records, with durable step attempt counters/start time established before capability invocation. Interrupted attempts receive a safe interruption outcome during fenced recovery. Max three attempts per step per execution, bounded exponential backoff with jitter, injected Clock/randomness for tests. Durable receipts are retained indefinitely in this phase (at least the documented 30-day horizon); no cleanup job silently breaks retry deduplication.

## Persistence and worker plan

Add V10 after inspected V9. Executions reference tenant-qualified workflow/version keys, pin version hash, input `{}`, actor, source/mode, fingerprint, deadline and optional retry lineage. Ordered steps own stable logical operation IDs and argument hashes. Separate insert-only attempts, timeline and record receipts/effects retain honest partial progress. Work is one durable row per execution with due time, owner, lease and monotonically increasing fencing token. Runtime gets only required column updates; append-only tables deny UPDATE/DELETE/TRUNCATE.

Claim uses short transactions and FOR UPDATE SKIP LOCKED, commits a lease/token before invoking work, and bounds global workers and active claims per tenant. Lock ordering is organization → execution → work; current actor account and membership/grants are checked through public boundaries before effects. All worker mutations check current token/owner/unexpired lease. Synthetic effect + receipt + step result/timeline commit atomically. Recovery reconciles receipts before retry, never repeats a committed effect and never revives a terminal execution. PostgreSQL failure stops submissions/claims; no memory-only acceptance. Polling performs durable deadline/lease/due reconciliation at startup and periodically. No transaction spans backoff or scheduled waiting.

## API and permission plan

All routes are below `/api/v1/organizations/{organization}` and use live service authorization, exact Origin/CSRF and bounded strict JSON. Existing mappings suffice: Owner/Admin/Operator run/cancel/retry; all tenant roles read; Builder cannot run solely from workflow.edit/publish. Worker context is explicit persisted organization/requesting actor, rechecked for current execution.run rather than relying on an expired browser session.

| Route | Contract |
| --- | --- |
| POST workflows/{workflow}/executions | `{versionId?,input:{},delaySeconds?:0..3600}`, Idempotency-Key; 202/200 summary and Location |
| GET executions | Bounded page/size, optional workflowId/status filter; persisted summaries |
| GET executions/{id} | State/revision, pinned version/hash, timestamps/deadline, current step, safe error, retry eligibility |
| GET executions/{id}/steps | Bounded ordered step DTOs and safe outputs |
| GET executions/{id}/attempts | Bounded append-only attempt outcomes |
| GET executions/{id}/events | Bounded ordered safe timeline |
| POST executions/{id}/cancel | `{expectedRevision,reason}`; cooperative durable cancellation, completed effects retained |
| POST executions/{id}/retry | `{expectedRevision,reason}`, Idempotency-Key; new linked execution, same snapshot and receipts |

No raw graphs, exception messages, credentials or unrestricted user payloads in events/audit. Foreign IDs concealed with 404, valid member missing permission 403, stale/idempotency/state conflict 409, bad types 400, unsupported runtime/input 422, dependency failure 503. Cancel/retry must roll back if required audit fails.

## Verification plan

Keep all Phase 1/2 tests. Add state-machine/backoff units; restricted-runtime PostgreSQL lifecycle/claim/recovery/fencing/receipts/partial outcome/cancel/deadline/security/audit tests; empty/V9 upgrade tests; HTTP and UI contracts; and actual Chromium start → execution → persisted history/navigation, denial and unsupported-node checks. Tests use disposable containers/synthetic identities and controlled failure seams in test sources, never the real account/database or SMTP. Preserve `.env`, keys, consumed marker, users, tenants and saved definitions on the additive local upgrade. Run README gates, scans, normal commits/pushes, exact remote refs and actual corresponding CI.

## Observed increments

- f78c70483ff6be405c8cb4c03104fff3895d8e2d: guarded state machine/backoff and accepted contracts. Three focused tests passed; [corresponding hosted CI passed](https://github.com/ChouaibSeishin/BridgeOps/actions/runs/39063792941).
- Runtime implementation checks: 43 integration cases passed (22 lifecycle, six execution HTTP, two execution migrations, 11 retained workflow HTTP and two retained workflow migration cases). A subsequent 25-case run passed final skipped-step accounting, fresh/V9 upgrade and an actual disposable PostgreSQL outage. No failures/errors/skips in these completed runs. Final regression and browser/Compose publication gates remain outstanding.
- Local preservation baseline was captured without printing row fields: users 1, organizations 1, workflows 3, published versions 2, consumed installation marker, V9. These current user-created definitions must be preserved; earlier Phase 2 empty-tenant counts are historical.

Runtime defaults: four global claims/workers, two active claims per organization, 30-second leases renewed while active, 1000ms polling, five-second bounded worker shutdown. Configuration is under bridgeops.executions.worker with enabled/parallel/per-organization/lease-seconds/poll-millis; invalid bounds fail startup. Runtime reauthorizes each dispatch/effect against current actor/tenant execution.run; browser session expiry alone does not cancel durable work. Unvisited steps after failure are SKIPPED, completed steps remain SUCCEEDED. Final details/commands and full release evidence will be recorded after verification.
