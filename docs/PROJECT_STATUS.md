# Project status

Updated 2026-10-19 UTC. **Phase 4 real human approval processing is complete and verified.** Publication stores an immutable definition; an explicit start executes supported internal capabilities. Integrations, webhooks, AI and MCP remain later phases.

## Verified Phase 4 delivery

Preflight verified clean local/fetched master b2985dd3d178d9fddda59f163d762de8b0ce0270 with successful corresponding hosted CI, V10 and three healthy Compose services. Existing data is one account/organization/membership, three workflows, two published versions, no executions/receipts and a consumed bootstrap marker. Private preservation fingerprints now include execution/receipt history and signing material/volume identity. The [Phase 4 contract](development/PHASE_4_APPROVALS.md) records fixed-role authorization, schema-v1 next-record interpretation, policy/defaults, exact intent, durable signals and lock ordering. Runtime/API/UI, full regression, preserved-data production upgrade and corresponding hosted CI are verified below.

V11 adds tenant-qualified policies, requests, immutable decisions, signals, consumption receipts, invalidations and events. Explicit gates and central record-policy gates share enforcement. Live requester/capability/approver authority, policy and exact intent are checked at decision, resumption and effect. Waiting releases worker leases; reconciliation handles final signals, cancellation, expiry and the unchanged absolute deadline. Completed effects remain visible.

Authenticated approval inbox/detail and Owner policy APIs/UI expose persisted decisions and execution state, read-only exact arguments, immutable workflow version/hash and human participant labels. A distinct eligible actor is mandatory even for Owners. The clean full regression passed 265 tests (61 unit/MVC + 204 PostgreSQL/browser), zero failures/errors/skips across 30 XML reports. All 68 frontend tests, strict typecheck, lint, build and production dependency audit passed (zero vulnerabilities). Fresh V11 and V10 upgrade coverage, both real browser journeys and retained Phase 1–3 tests passed. The actual V11 Compose upgrade is healthy, with unchanged private table/material/volume fingerprints, one account/tenant/membership, three workflows, two versions, no execution/approval test records and consumed bootstrap marker. The corresponding hosted implementation run passed, as recorded below.

Historical reporting correction: Phase 3 secret scans ran locally. Its hosted workflow had no secret-scan step, despite previous wording. Phase 4 adds an explicit pinned, checksum-verified Gitleaks history scan while retaining existing CI gates.

Implementation commit **5d34fbad5c87a521417e491fa2fc474c20c78938** was normally pushed to master, and the exact remote ref matched. [Its hosted run completed successfully](https://github.com/ChouaibSeishin/BridgeOps/actions/runs/39294922314): history secret scan, documentation structure, 68 frontend tests/typecheck/lint/build, complete 265-test backend/PostgreSQL/Chromium suite, strict report gate, production container build/start, same-origin smoke and zero-vulnerability dependency audit. Local production and anonymous approval-route security smoke also passed without logging into the user's account. The final documentation checkpoint references this observed implementation run; its own hosted result is checked separately. Earlier failed schema/runtime runs and the two corrected legacy schema expectations are recorded honestly in the approval guide.

## Historical Phase 3 delivery

Preflight found clean local/fetched master b0bce52797765e475b7c4a07481802b928eb6304 with [successful corresponding CI](https://github.com/ChouaibSeishin/BridgeOps/actions/runs/38955900005), V9 and healthy Compose. The actual local user had since created one organization, three workflows and two published versions; all are preserved as user data, never used as fixtures. Aggregate table/material/volume fingerprints were captured without printing private rows or keys.

| Phase 3 commit | Verified increment | Hosted evidence |
| --- | --- | --- |
| f78c70483ff6be405c8cb4c03104fff3895d8e2d | Guarded state machine/backoff and accepted runtime contracts | [Passed](https://github.com/ChouaibSeishin/BridgeOps/actions/runs/39063792941) |
| 0dcea6ca40ee410c4b52cdec5dac5deca12866dd | V10, fenced PostgreSQL worker, idempotent record receipts, recovery and authorized HTTP lifecycle | [Passed](https://github.com/ChouaibSeishin/BridgeOps/actions/runs/39065711991) |
| 840bab356e95248d45db7959d9955404823d1e82 | Monitoring UI, human-readable labels and expanded browser journey | [Failed](https://github.com/ChouaibSeishin/BridgeOps/actions/runs/39066751869) at retained logout assertion after test workload exhausted the unchanged auth peer quota; resolved in the next increment |
| b736aa5f4ded479af3918f199a1c17032eb38c5e | Recovery lock ordering, bounded pool, complete regression and preserved-data/container/browser release gates | [Passed](https://github.com/ChouaibSeishin/BridgeOps/actions/runs/39070309725) |

The complete local regression passed **206 tests (54 unit/MVC + 152 PostgreSQL/browser)** with zero failures/errors/skips across 24 XML reports. All **54 frontend tests**, strict typecheck, lint and production build passed; production dependency audit found zero vulnerabilities. Coverage includes 31 execution lifecycle cases, six execution HTTP cases, fresh/V9 migrations, runtime privileges, actual disposable PostgreSQL outage and the expanded Chromium journey with persisted database assertions. Final focused checks cover the retry timestamp, organization history link and publication wording. The V10 local upgrade preserved table/material/volume fingerprints, account, consumed bootstrap marker, tenant and saved workflows. All three production containers and anonymous same-origin security smoke passed. The corresponding hosted implementation run passed frontend checks, required backend/PostgreSQL/Chromium tests, strict report gate, container startup, same-origin smoke and dependency audit. Secret scans ran locally; the hosted scan claim was corrected above. Its head SHA was verified as b736aa5f4ded479af3918f199a1c17032eb38c5e. [Exact contracts, implementation map, commands and limits](development/PHASE_3_EXECUTION.md).

## Historical Phase 2 starting point and preserved environment

Phase 2 inspection found clean master and fetched GitHub head both at 11aec6e9e2e9d2e0ea0aaedde2e286de1191e538, with V8 and no workflow source/tables. The user had provisioned one local account: users 1, organizations 0, bootstrap marker consumed. Tests use disposable PostgreSQL and synthetic accounts, not that local identity. Environment, RSA/CSRF keys and named volume are preserved; bootstrap is never rerun. An aggregate account-state fingerprint/count baseline was captured without printing user fields/credentials for the additive V9 upgrade comparison.

## Historical Phase 2 published increments

Normal commits/pushes and exact remote refs were verified; no reset, force push or history replacement.

| Commit | Increment | Actual hosted outcome |
| --- | --- | --- |
| 4875c64 | Reconcile stale Git/Phase 1 docs, accept ADR-0004 and Phase 2 contract | [Passed](https://github.com/ChouaibSeishin/BridgeOps/actions/runs/38943543050) |
| 11555f5 | Versioned node registry, deterministic validator and canonical hash | [Passed](https://github.com/ChouaibSeishin/BridgeOps/actions/runs/38944732833) |
| 891cf82 | V9 drafts/immutable versions, public tenant/audit boundary, atomic publication | [Passed](https://github.com/ChouaibSeishin/BridgeOps/actions/runs/38946336182) |
| 77411d1 | Workflow HTTP lifecycle, strict tenant/revision/security contracts | [Passed](https://github.com/ChouaibSeishin/BridgeOps/actions/runs/38947425562) |
| ee7c98b | Browser authoring, validation, publication/history, duplication/archive | [Passed](https://github.com/ChouaibSeishin/BridgeOps/actions/runs/38949564604) |
| c5e588e | Strict draft inputs, full browser lifecycle and final release verification | [Passed](https://github.com/ChouaibSeishin/BridgeOps/actions/runs/38955049541) |

Final implementation commit: c5e588e5f929f7bac782196b481597b44cdb34ad. Its corresponding hosted run completed successfully, including frontend, required backend/PostgreSQL/Chromium verification, container build/start, same-origin smoke and production dependency audit. Final hosted completion was observed directly.

## Implemented Phase 2 behavior

- Dedicated workflows domain/application/persistence/web module, application DTOs and public TenantAuthorization/WorkflowAudit contracts. No cross-module entity/repository imports; a source-import boundary test checks this rule.
- Schema-v1 manualTrigger/syntheticAction/approval/end registry with versioned typed config, one connected linear path and deterministic bounded errors. No arbitrary script, SQL, expressions, connector URLs or external dispatch. Definition support is distinct from execution support.
- Bounded request parsing, duplicate-key/depth/string/number checks, exact integer revisions/string metadata and bounded graph values/bytes/nodes/edges. Invalid bounded drafts remain editable but cannot publish.
- Organization-qualified workflow/draft/version keys, current-pointer consistency, source-revision uniqueness, column-scoped runtime grants, immutable-row guard and database SHA-256 check. No runtime UPDATE/DELETE/TRUNCATE on versions and no physical workflow delete.
- Save/reload with optimistic revision; publication locks live account → organization → workflow/draft, reauthorizes, validates persisted content, canonicalizes, allocates a workflow-local version, updates pointer/consumes revision and writes required safe audit in one transaction. Audit failure rolls back publication. Historical graph and metadata remain unchanged.
- Live workflow.read/edit/publish capabilities use existing fixed role mappings; no new grant or role editor. Foreign resources are concealed; stale revisions conflict. Duplicate copies the persisted draft independently; archive preserves historical reads.
- Responsive form editor integrated with real organization/session/query APIs: schema-derived config, explicit connections, saved/unsaved state, node/field errors, exact-revision publish confirmation, actual version/hash/actor history and read-only snapshots. Conflicts preserve local edits; discard/reload is explicit. No Run/Test Run control or fabricated execution metrics.

## Historical Phase 2 verification observed

| Gate | Executed evidence |
| --- | --- |
| Validator/boundary | 28 graph cases + one import-boundary unit case passed; canonical/hash/order, graph/schema/config/count/parser limits and safe errors |
| Lifecycle/migrations | 10 lifecycle + 2 migration cases passed using bridgeops_app and a separate disposable operator; fresh schema and V8 upgrade preserve existing account/tenant/marker/role mappings |
| HTTP | All 11 restricted-runtime HTTP cases passed in full regression, including exact scalar types and safe 422/409/401/403/404/413/503 behavior |
| Frontend | Final 37 tests, strict typecheck, lint and production build passed; production dependency audit found 0 vulnerabilities |
| Real Chromium | Complete workflow/onboarding/session/recovery journey and database assertions passed in the final full run. Desktop/mobile/dark captures reviewed; mobile overflow and 44px checks passed |
| Full Maven | BUILD SUCCESS: 162 tests (50 unit/MVC + 112 required PostgreSQL/browser), zero failures/errors/skips; 18 XML reports checked |
| Compose/local preservation | Config/build/start passed, all three services healthy, V1–V9 installed. Account/installation fingerprints unchanged; users 1, organizations/workflows/versions 0, marker consumed. Version UPDATE/DELETE and user INSERT denied; guard owned by bridgeops_migrator |
| Documentation/secrets | Current link/fence checks and per-increment staged/history scans passed. Jar/production assets contain no checked fixture classes/routes/passwords. Environment/keys/user data/test outputs remain untracked |

The browser initially exposed locator mismatches for nested select/textarea labels; role-based locators corrected these without removing assertions. Capture scroll positions are normalized. Malformed saved operation values remain safely correctable without arbitrary object-to-string conversion. A build alone is not browser evidence.

Actual nginx smoke passed info/liveness/readiness/CSRF 200 with no-store bootstrap, anonymous workflow GET 401, missing-CSRF mutation 403 and current editor asset checks. A fresh anonymous browser verified actual sign-in/protected navigation with no persistent credential storage and no account login. SMTP remains disabled; no real provider/recipient was contacted.

## Historical Phase 1 checkpoint

Phase 1D completed at 11aec6e with [successful final CI](https://github.com/ChouaibSeishin/BridgeOps/actions/runs/38931957774): 110 backend/integration/browser tests and 17 frontend tests. These counts and the then-empty local installation are historical, not current suite/state. Earlier foundation/auth/session/bootstrap/tenant/invitation work remains in Git history and the [Phase 1 verification](development/PHASE_1_VERIFICATION.md), [onboarding](development/PHASE_1C_ONBOARDING.md) and [browser/recovery](development/PHASE_1D_BROWSER.md) guides.

## Operation, limits and next work

Use [README](../README.md), the [approval guide](development/PHASE_4_APPROVALS.md) and the [execution contract/commands](development/PHASE_3_EXECUTION.md). Existing installations retain their environment, keys, account and volume; only fresh installations use the one-shot offline bootstrap. External SMTP stays disabled until externally configured and was not contacted during verification.

Current definition semantics are linear only. Phase 4 runs published manual-trigger/internal-record/approval/end chains, with an approval targeting its next record. Branches/joins, TEST snapshots and external effects remain deferred. Unsaved editor state lives only in the current page. Hashes identify content, not authorship/tamper-proof storage. Publication does not run anything. No production deployment/security/load/TLS/HA, WCAG conformance, backend vulnerability audit, backup/restore, RLS, SSO/MFA or compromised-runtime containment is claimed. Local Compose still supplies startup migration credentials; production must separate migration/operator execution. No license or verified private reporting channel is configured. SMTP dispatcher/outbox limitations from Phase 1 remain unchanged.

Phase 4 implements human approvals for internal records. Controlled integration/webhook work follows in Phase 5 and AI/MCP in Phase 6; neither later phase was started.
