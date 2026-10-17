# Phase 3 — durable sequential execution

Status: implemented; local release gates passed, final hosted verification pending. Started from verified clean master b0bce52797765e475b7c4a07481802b928eb6304 on 2026-10-17. Prior Phase 2 test counts and local data counts are historical. No Phase 3 completion is claimed before final regression, preserved-data upgrade and corresponding hosted CI succeed.

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
- 0dcea6ca40ee410c4b52cdec5dac5deca12866dd: durable runtime/API increment; [corresponding hosted CI passed](https://github.com/ChouaibSeishin/BridgeOps/actions/runs/39065711991).
- Runtime implementation checks: 43 integration cases passed (22 lifecycle, six execution HTTP, two execution migrations, 11 retained workflow HTTP and two retained workflow migration cases). A subsequent 25-case run passed final skipped-step accounting, fresh/V9 upgrade and an actual disposable PostgreSQL outage. No failures/errors/skips in these completed runs. These increment counts are historical; final local results are recorded below.
- Local preservation baseline was captured without printing row fields: users 1, organizations 1, workflows 3, published versions 2, consumed installation marker, V9. These current user-created definitions must be preserved; earlier Phase 2 empty-tenant counts are historical.

Runtime defaults: four global claims/workers, two active claims per organization, 30-second leases renewed while active, 1000ms polling, five-second bounded worker shutdown. Configuration is under bridgeops.executions.worker with enabled/parallel/per-organization/lease-seconds/poll-millis; invalid bounds fail startup. Runtime reauthorizes each dispatch/effect against current actor/tenant execution.run; browser session expiry alone does not cancel durable work. Unvisited steps after failure are SKIPPED, completed steps remain SUCCEEDED. Final details/commands and release evidence appear below.

## Implemented DTOs, bounds and operation semantics

Summary fields are id/workflowId/versionId/definitionHash/requestedBy/source/mode/status/revision/currentPosition, createdAt/startedAt/completedAt/deadlineAt/notBefore, errorCode/retryOfExecutionId/retryEligible. Start/retry return the summary directly and Location, not an acceptance/success placeholder. Steps return an ordered list of at most 50 entries with nodeId/position/type/status/attemptCount/startedAt/completedAt/output/errorCode/retryAt. Attempts and events use Page (items/page/size/totalElements/totalPages). Attempts retain immutable start/end/outcome/error records; an in-progress attempt is represented by the durable RUNNING step/counter/start timestamp until its immutable outcome is inserted. Event fields are sequence/occurredAt/code/nodeId. No graph, user message or raw exception is copied into timeline/audit.

List page is 0–100000 and size 1–100 (default 25); optional workflowId/status filters are tenant-qualified. Attempts/events default to 100 and remain pageable. Idempotency-Key is 8–128 ASCII letters/digits/underscore/hyphen; UUID keys work. Strict JSON rejects duplicate keys, non-string metadata/reason/version IDs, fractional/string/null/negative/overflow revisions and non-integer delays; request body is capped at 256 KiB before binding. Input is exactly empty object. Reason is required, 1–200 characters without controls, and is not copied into arbitrary audit payloads.

Automatic transient retry delays are 1000–1500ms then 2000–2500ms, for three total attempts. Permanent, permission, deadline and reconciliation-required failures do not retry. Interrupted pre-effect attempts count toward the limit. Manual retry creates fresh step attempts while preserving logical operation keys from the source; successful prior record effects are reconciled through their argument-hash receipt. Source execution/attempt history remains terminal and unchanged. Archive prevents new starts but does not silently cancel already accepted immutable executions. Session logout alone does not revoke the persisted execution actor's tenant authority; disabling the account/removing grants does prevent subsequent dispatch/effects.

Only the built-in synthetic.record capability exists. Its effect is an insert-only bounded internal message row, with a stable receipt and safe `{recorded:true}` result. No external call, notification or business connector is implied. Receipt/effect retention is indefinite in this phase; no cleanup shortens the documented deduplication horizon. A synthetic preparation/after-commit seam is overridden only in test sources to exercise failures/interruption; there are no public fake-failure flags.

## Configuration and verification commands

Compose forwards optional EXECUTION_WORKER_ENABLED/PARALLEL/PER_ORGANIZATION/LEASE_SECONDS/POLL_MILLIS into canonical bridgeops.executions.worker properties. Defaults are true/4/2/30/1000. Parallel range 1–16, tenant limit 1–parallel, lease 5–120s, poll 250–5000ms with more than three polling intervals per lease. Worker shutdown waits at most five seconds, then interrupts tasks; abandoned claims recover from PostgreSQL after lease expiry. Reconciliation handles up to 100 expired/deadline candidates per cycle. Due READY work is claimed independently of process memory, so a fresh runtime resumes persisted waits. No transaction spans a wait/preparation hook. The internal effect transaction holds current fencing/ownership locks through commit; stale workers cannot update a newer lease. Admission serializes global claim counts with a transaction advisory lock, then uses organization/execution/work SKIP LOCKED locking. No throughput or external exactly-once guarantee is claimed.

Use Java 21, pinned Node 24.22.0, Docker and installed Playwright Chromium as in README. From the repository root:

```powershell
npm.cmd --prefix frontend ci --no-audit --no-fund
npm.cmd --prefix frontend run typecheck
npm.cmd --prefix frontend run lint
npm.cmd --prefix frontend test
npm.cmd --prefix frontend run build
npm.cmd --prefix frontend exec -- playwright install chromium
./backend/mvnw.cmd -f backend/pom.xml -B -ntp clean verify
node scripts/verify-test-reports.mjs
node scripts/verify-docs.mjs
npm.cmd --prefix frontend audit --omit=dev --audit-level=high
docker compose --env-file .env -f infra/compose.yaml config --quiet
docker compose --env-file .env -f infra/compose.yaml up --build -d --wait --wait-timeout 180
```

Set BRIDGEOPS_TEST_NODE to the absolute pinned node executable when the host default differs. Focused integration commands may use `-Dit.test=ExecutionLifecycleIT,ExecutionHttpIT,ExecutionMigrationIT,ExecutionOutageIT` with test-compile/failsafe:integration-test/failsafe:verify. All identity/organization/workflow tests and required browser journey remain in full verify. The report gate rejects missing required runtime/browser suites, empty suites and any failures/errors/skips. Documentation checks cover local links/file presence/fences, not external URLs or Mermaid rendering. Keep `.env`, signing keys, local tools, test outputs and user data ignored; scan exact staged content/history before every normal publication.

## Implementation map and remaining boundaries

Disposable Chromium captures: [execution desktop](../screenshots/phase3-execution-desktop.png), [dark](../screenshots/phase3-execution-dark.png), [mobile](../screenshots/phase3-execution-mobile.png) and [workflow labels](../screenshots/phase3-workflow-desktop.png). These show synthetic test data, not the preserved local account.

- V10 owns execution/step/attempt/event/work/record/receipt tables and guards/grants.
- executions.domain owns state/graph order/backoff; application owns submission, worker, recovery and safe views; persistence owns runtime SQL; web owns strict authenticated routes/errors.
- WorkflowDefinitions/WorkflowRuntime, TenantAuthorization and ExecutionAudit are public module boundaries; source-import tests prohibit cross-module persistence/domain imports.
- frontend/features/executions owns API/start/list/detail/attempt/timeline UI. Workflow model/GraphEditor derive friendly labels using graph order with deterministic fallback; stable IDs and canonical definitions are untouched.
- ExecutionStateTest/ExecutionBoundariesTest, ExecutionLifecycleIT/ExecutionHttpIT/ExecutionMigrationIT/ExecutionOutageIT and the extended BrowserJourneyIT verify the new critical path alongside all prior suites.

External SMTP stays disabled/unverified; no real provider is contacted by tests. Draft TEST snapshots, graph delay/branch/transform types, approval requests/decisions, external connectors/webhooks, AI/MCP, retention jobs and production load/HA/security/WCAG certification remain unimplemented. The existing local Compose migration-credential boundary remains unchanged; production must separate migrations/operator authority. This engine establishes local atomic record effects and observable recovery, not a distributed exactly-once claim.

Historical UI increment verification: 53 frontend cases, strict typecheck/lint/production build and production dependency audit passed. The expanded real Chromium journey passed with database assertions for four executions (two successful, one cancelled scheduled start, one exhausted transient failure with a linked successful retry), four committed records/receipts, exactly one receipt reuse and no executions from Phase 2/approval definitions. It retained all onboarding/session/recovery and definition checks. Desktop/mobile/dark captures use disposable synthetic accounts and were inspected; no real account or SMTP was used. Recovery refinements are included in the final local results below.

Release refinements preserve actor → organization → execution → work lock order, including terminal reconciliation for disabled actors/archived tenants without granting dispatch authority. Thirty-one lifecycle cases cover those paths, cancellation races, cross-tenant/global admission, reconstructed-runtime scheduled recovery and receipt-argument conflicts. Worker thread and dispatch queues are explicitly bounded; production defaults stay unchanged. Deterministic service/HTTP suites disable automatic polling, while BrowserJourneyIT explicitly enables the real pool/timer and stops it before disposing the database.

The first complete clean local regression passed 203 cases (54 unit/MVC + 149 PostgreSQL/browser). Subsequent archived/admission/receipt coverage adds three cases; the final complete run passed 206 cases (54 unit/MVC + 152 PostgreSQL/browser), zero failures/errors/skips across 24 XML reports. Hosted UI run 39066751869 correctly failed at the retained logout-revocation check after the longer fast journey exhausted the shared authentication peer quota. The test now paces its workload across the existing one-minute window and respects observed Retry-After; production quotas are unchanged. Read-only revocation probes permit two ECONNRESET retries (never HTTP status retries), and logout must explicitly return 204 before the original 401 assertion. The corrected expanded browser journey passed again locally. Final hosted release success remains pending until observed.

The additive actual V10 Compose upgrade passed with all three services healthy. Account/installation/organization/role/draft/version fingerprints, environment/key hashes and named-volume identity remained identical. Actual counts: users 1, organizations 1, workflows 3, published versions 2, executions/record effects 0, bootstrap consumed. No local fixture, account login or SMTP delivery was used. Actual nginx anonymous Chromium smoke passed current runtime assets, protected navigation, empty persistent storage, health/CSRF 200, anonymous execution GET 401 and missing-CSRF/hostile-Origin POST 403. The final image refresh and anonymous smoke also passed with these preservation checks.

The monitor exposes the persisted retryAt timestamp on a waiting step, not an estimated client timer. Organization detail provides an execution-history entry independently of workflow archive status. Publication notices describe only the publication action, never infer that no other actor has executed the version. These final read-only/UI refinements receive focused unit/HTTP/lifecycle/browser checks after the complete regression.

Final local delivery evidence: 54 frontend tests, strict typecheck/lint/build; complete Maven verify 206 tests; focused package plus BrowserJourneyIT/ExecutionHttpIT/ExecutionLifecycleIT after the final read-only/UI changes; required report gate, documentation checks, production dependency audit and staged/history secret scans. Final production Compose rebuild/start and anonymous security smoke passed; no private account login or test effects touched the local volume. Hosted completion is recorded only after the corresponding run finishes successfully.
