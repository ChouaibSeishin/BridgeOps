# Project status

Updated 2026-10-16 UTC. **Phase 2 workflow definitions are complete: full local release verification and the corresponding hosted implementation CI passed.** Publication stores an immutable definition; it does not execute it. Execution, approval processing, integrations, webhooks, AI and MCP remain later phases.

## Starting point and preserved environment

Phase 2 inspection found clean master and fetched GitHub head both at 11aec6e9e2e9d2e0ea0aaedde2e286de1191e538, with V8 and no workflow source/tables. The user had provisioned one local account: users 1, organizations 0, bootstrap marker consumed. Tests use disposable PostgreSQL and synthetic accounts, not that local identity. Environment, RSA/CSRF keys and named volume are preserved; bootstrap is never rerun. An aggregate account-state fingerprint/count baseline was captured without printing user fields/credentials for the additive V9 upgrade comparison.

## Verified published increments

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

## Verification observed

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

Use [README](../README.md) and the [Phase 2 contract/commands](development/PHASE_2_WORKFLOWS.md). Existing installations retain their environment, keys, account and volume; only fresh installations use the one-shot offline bootstrap. External SMTP stays disabled until externally configured and was not contacted during verification.

Current definition semantics are linear only; branches/joins, runtime work, TEST snapshots and approval decisions are deferred. Unsaved editor state lives only in the current page. Hashes identify content, not authorship/tamper-proof storage. Publication does not run anything. No production deployment/security/load/TLS/HA, WCAG conformance, backend vulnerability audit, backup/restore, RLS, SSO/MFA or compromised-runtime containment is claimed. Local Compose still supplies startup migration credentials; production must separate migration/operator execution. No license or verified private reporting channel is configured. SMTP dispatcher/outbox limitations from Phase 1 remain unchanged.

Phase 2 release gates passed. The next task is Phase 3 durable sequential execution with deterministic synthetic capabilities, state/attempt history, idempotency, retries/delay/deadline/cancel and fencing. Approval processing begins in Phase 4; integration/webhook and AI/MCP phases follow. No later-phase implementation was started.
