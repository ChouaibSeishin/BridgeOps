# Project status

Updated 2026-10-16 UTC. **Phase 1D implementation, full local verification and the real-browser journey are complete; final publication/hosted CI are pending.** This is a development checkpoint, not production certification. Workflow execution, integrations, approvals and AI/MCP were not started.

## Continuity and publication evidence

Initial inspection found clean local master exactly matching fetched origin/master at ed1aaa88d09f47a5d9bd3ee8ceff43e1d3afdd3f (0 ahead/behind). Existing branch history, ignored .env/signing keys and database volume were preserved.

| Increment | Verified publication |
| --- | --- |
| Phase 1C offline bootstrap / organizations / membership / invitations | 267645e, 43338a8, f12ce04, 7aec5ea; [final checkpoint CI passed](https://github.com/ChouaibSeishin/BridgeOps/actions/runs/38899423907) |
| 1D.1 browser sign-in, memory-only session coordination and sign-out | 54a8c1f; push/ref verified; [CI passed](https://github.com/ChouaibSeishin/BridgeOps/actions/runs/38907270990) |
| 1D.2 organizations, members and invitation browser journeys / safe read contracts | 078c3e2; push/ref verified; [CI passed](https://github.com/ChouaibSeishin/BridgeOps/actions/runs/38909776173) |
| 1D.3 password change/recovery, V8 and neutral shared mail port | 594e0d3; push/ref verified; [CI passed](https://github.com/ChouaibSeishin/BridgeOps/actions/runs/38911492628) |

6416a5c was pushed/ref-verified. Its [first hosted browser run failed](https://github.com/ChouaibSeishin/BridgeOps/actions/runs/38914798975) while the other 88 integration cases passed. Local full verification passed; Linux browser diagnostics and final touch/keyboard refinements are being verified. No hosted completion is claimed until the retry succeeds.

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
| Actual persistent state/smoke | V1–V8 installed; users/organizations/reset proofs 0, marker unconsumed, SMTP disabled; runtime user INSERT/proof expiry UPDATE denied and consumption allowed; info/readiness/CSRF 200, missing-CSRF forgot 403, unauthenticated me 401 |
| Documentation/secrets | Local link/fence checker and staged whitespace/secret scan run at each publication gate; actual .env/signing material remains untracked |

Browser fixtures use isolated Testcontainers PostgreSQL, explicit operator bootstrap and a secret-protected loopback gateway/mail capture in test sources. Production jar/images have no such fixture route. No persistent local account or real-recipient mail was created. The Windows JDK selector failure was resolved using a canonical ignored test socket directory; no OS setting or app security control changed. Build success alone is not browser evidence.

## Operation, limits and next work

Follow [clean-checkout startup](../README.md), [offline bootstrap/SMTP](development/PHASE_1C_ONBOARDING.md) and [browser/recovery guide](development/PHASE_1D_BROWSER.md). The normal local stack requires an operator-provisioned identity before manual sign-in. SMTP is disabled until externally configured; no provider credentials/connectivity or actual recipient delivery was tested. Forgot responses remain generic when delivery is disabled/failed. The volatile dispatcher may discard/lose work; no durable outbox, retry or email/DB atomicity guarantee. A delivered-but-rolled-back proof is unusable and tested.

Java 21 / Boot 4.1.1 / Maven Wrapper 3.9.16 / PostgreSQL 18.6 and the React/TypeScript baseline are retained; pinned Playwright 1.64.1 is development-only, Node is 24.22.0. Full verify now requires built frontend assets and installed Chromium and fails if prerequisites are missing.

No production deployment/security/load/TLS/HA, WCAG conformance, backend vulnerability audit, backup/restore, RLS, SSO/MFA or compromised-runtime containment claim. Local Compose still supplies startup migration credentials; production must separate operator/migration execution. No license or verified private reporting channel is configured. After Phase 1D final verification, next is Phase 2 workflow draft persistence/validation/publication; execution, approvals, integrations and AI/MCP remain later.
