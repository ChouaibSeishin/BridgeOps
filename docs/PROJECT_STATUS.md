# Project status

Updated 2026-10-16 UTC. **Phase 1D is complete; Phase 2 workflow definitions are in progress.** This is a development checkpoint, not production certification. Workflow execution, integrations, approval processing and AI/MCP were not started.

Phase 2 inspection: clean master and GitHub both at 11aec6e9e2e9d2e0ea0aaedde2e286de1191e538, highest migration V8, no workflow source/tables. Actual local state now has one user, zero organizations and a consumed bootstrap marker. The user-created account, environment, keys and volume are preserved. Zero-account observations below describe earlier disposable/local Phase 1 verification, not the current installation. [Accepted Phase 2 design and progress](development/PHASE_2_WORKFLOWS.md): graph schema/limits, existing role grants, persistence/publication lock and hash contracts. No Phase 2 code/test completion is claimed yet.

## Continuity and publication evidence

Phase 2 HTTP progress: workflow list/create/detail/save/validate/publish/duplicate/archive/history/exact-version endpoints are implemented, with current tenant capability checks, exact Origin/CSRF, safe semantic 422 details, revision conflicts and bounded parsing. Ten restricted-runtime HTTP cases plus 29 selected validator/boundary unit cases passed. Persistence 891cf82 is pushed/ref-verified and [hosted CI passed](https://github.com/ChouaibSeishin/BridgeOps/actions/runs/38946336182). UI/browser/release verification remains in progress; execution stays unavailable.

Phase 2 persistence progress: V9, public TenantAuthorization, narrow WorkflowAudit and workflow application/persistence services are implemented. All 12 focused disposable PostgreSQL lifecycle/migration tests passed, including V8 preservation, races, immutable rows and audit rollback. HTTP/UI are still unavailable pending their increments. Local data remains preserved. Validator increment 11555f5 is pushed/ref-verified and [hosted CI passed](https://github.com/ChouaibSeishin/BridgeOps/actions/runs/38944732833).

Phase 2 progress: design/document reconciliation 4875c64 is pushed/ref-verified and [hosted CI passed](https://github.com/ChouaibSeishin/BridgeOps/actions/runs/38943543050). The first code increment implements only the bounded definition registry, validator and canonical SHA-256 format; its 28 focused unit cases passed. Persistence, HTTP/UI and full Phase 2 gates remain in progress; no execution is implemented.

Initial inspection found clean local master exactly matching fetched origin/master at ed1aaa88d09f47a5d9bd3ee8ceff43e1d3afdd3f (0 ahead/behind). Existing branch history, ignored .env/signing keys and database volume were preserved.

| Increment | Verified publication |
| --- | --- |
| Phase 1C offline bootstrap / organizations / membership / invitations | 267645e, 43338a8, f12ce04, 7aec5ea; [final checkpoint CI passed](https://github.com/ChouaibSeishin/BridgeOps/actions/runs/38899423907) |
| 1D.1 browser sign-in, memory-only session coordination and sign-out | 54a8c1f; push/ref verified; [CI passed](https://github.com/ChouaibSeishin/BridgeOps/actions/runs/38907270990) |
| 1D.2 organizations, members and invitation browser journeys / safe read contracts | 078c3e2; push/ref verified; [CI passed](https://github.com/ChouaibSeishin/BridgeOps/actions/runs/38909776173) |
| 1D.3 password change/recovery, V8 and neutral shared mail port | 594e0d3; push/ref verified; [CI passed](https://github.com/ChouaibSeishin/BridgeOps/actions/runs/38911492628) |
| 1D.4 required Chromium journey, responsive workspace, release gates and recovery navigation synchronization | 6416a5c, 3275ba9, ab9890a, ceeb301; normal pushes/ref verified; [final implementation CI passed](https://github.com/ChouaibSeishin/BridgeOps/actions/runs/38931044047) for ceeb30180a09c9edcc589693384818b8cfe01a90 |

Initial hosted browser runs failed at recovery while the other 88 integration cases passed. Safe substep/source diagnostics pinpointed the acknowledgement assertion with no forgot POST. The test now waits for the destination recovery heading before filling Email, which also exists on the outgoing sign-in route. Local Chromium and the full Linux hosted workflow then passed. No assertion was skipped and no application security control was weakened. Diagnostics expose method/path/status and source locations, excluding credential-bearing assertion messages; cleanup tolerates an environment that was never created.

## Implemented behavior

- Explicit disabled-by-default offline one-shot verified-account bootstrap, locked consumed marker and account-only organization.create grant. Normal startup provisions no account or tenant.
- RS256 access credentials in browser memory; opaque rotating HttpOnly refresh cookies, signed CSRF/exact Origin, live account/family checks and shared bounded throttling. Cookie-sensitive sequences and protected requests use an origin-wide Web Lock; broadcasts carry only invalidation state. Unsupported browsers fail closed.
- Actual sign-in/protected shell, initial CSRF/refresh/me restoration, expiry/401 coordination, safe failures, this-browser sign-out and logout-all. No individual remote-session list or persistent credential cache.
- Own organization listing/creation, actual empty states/totals, tenant detail/member/role display, revision-checked member changes/removal, delegation ceilings and concurrent last-active-Owner protection. Member labels cross only identity's public DTO boundary after tenant authorization.
- Live create-organization capability, tenant access/assignable-role hints and bounded invitation history. These are UI hints; all mutations recheck current server authority. Recipient-bound invitation issue/revoke, authenticated acceptance and first-time enrollment with explicit subsequent sign-in.
- Current-password-confirmed change, hash-only one-use 15-minute recovery proofs, strict expiry/current eligibility, all-family/proof invalidation, safe transactional audit and bounded quotas. Generic forgot acknowledgement uses a bounded volatile dispatcher and never confirms mail delivery.
- Neutral shared authenticated TLS mail transport; existing INVITATION_SMTP_* settings remain compatible and disabled by default. No production fake mailbox or test bootstrap route.
- Truthful responsive workspace grounded in existing design tokens, real API data, loading/empty/error/retry states and functional controls; unavailable later capabilities omitted.

## Fresh verification

| Gate | Executed result |
| --- | --- |
| 1D.1 frontend | 11 tests/typecheck/lint/build passed; complete hosted pipeline passed |
| 1D.2 contracts | 37 restricted-runtime PostgreSQL cases passed, final direct capability assertion also passed; frontend 14 tests and static/build gates passed |
| 1D.3 recovery | Final 14 PostgreSQL cases passed, including concurrency, strict expiry, supersession, account eligibility, replay, session revocation, quotas, mail/audit rollback and narrow privileges; 21 unit/MVC cases and four transport cases passed in focused runs; frontend 17 tests/typecheck/lint/build passed |
| Focused Chromium journey | Passed real sign-in/invalid credentials, concurrent tabs/reload, organization create/member views, invitation issue/revoke/enroll, denied global creation/foreign tenant, revision edit, sign-out, recovery invalidation of an independent session, password change and logout-all |
| Browser credential checks | HttpOnly refresh, no persistent browser credential storage, no token-bearing URLs and no-store authentication responses checked |
| Visual inspection | Actual disposable-fixture desktop/mobile/dark captures reviewed; mobile horizontal navigation overflow found and fixed; 390px document-width assertion passed |
| Final full backend | BUILD SUCCESS, 110 total: 21 unit/MVC + 89 required integration/browser cases, zero failures/errors/skips; XML reports checked |
| Final frontend/container | 17 frontend tests, typecheck/lint/production build; dependency audit 0 vulnerabilities; Compose config/build/start passed, all three services healthy |
| Final hosted implementation gate | ceeb301 workflow completed SUCCESS: 21 unit/MVC + 89 integration/browser cases, zero failures/errors/skips; frontend checks, Chromium installation, container build/healthy startup, same-origin API/CSRF smoke, audit 0 vulnerabilities and cleanup passed |
| Actual persistent state/smoke | V1–V8 installed; users/organizations/reset proofs 0, marker unconsumed, SMTP disabled; runtime user INSERT/proof expiry UPDATE denied and consumption allowed; info/readiness/CSRF 200, missing-CSRF forgot 403, unauthenticated me 401 |
| Documentation/secrets | Local link/fence checker and staged whitespace/secret scan run at each publication gate; actual .env/signing material remains untracked |

Browser fixtures use isolated Testcontainers PostgreSQL, explicit operator bootstrap and a secret-protected loopback gateway/mail capture in test sources. Production jar/images have no such fixture route. No persistent local account or real-recipient mail was created. The Windows JDK selector failure was resolved using a canonical ignored test socket directory; no OS setting or app security control changed. Build success alone is not browser evidence.

## Operation, limits and next work

Follow [clean-checkout startup](../README.md), [offline bootstrap/SMTP](development/PHASE_1C_ONBOARDING.md) and [browser/recovery guide](development/PHASE_1D_BROWSER.md). The normal local stack requires an operator-provisioned identity before manual sign-in. SMTP is disabled until externally configured; no provider credentials/connectivity or actual recipient delivery was tested. Forgot responses remain generic when delivery is disabled/failed. The volatile dispatcher may discard/lose work; no durable outbox, retry or email/DB atomicity guarantee. A delivered-but-rolled-back proof is unusable and tested.

Java 21 / Boot 4.1.1 / Maven Wrapper 3.9.16 / PostgreSQL 18.6 and the React/TypeScript baseline are retained; pinned Playwright 1.64.1 is development-only, Node is 24.22.0. Full verify now requires built frontend assets and installed Chromium and fails if prerequisites are missing.

No production deployment/security/load/TLS/HA, WCAG conformance, backend vulnerability audit, backup/restore, RLS, SSO/MFA or compromised-runtime containment claim. Local Compose still supplies startup migration credentials; production must separate operator/migration execution. No license or verified private reporting channel is configured. The next incomplete roadmap task is Phase 2 workflow draft persistence/validation/publication; execution, approvals, integrations and AI/MCP remain later.
