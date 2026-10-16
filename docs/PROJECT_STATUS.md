# Project status

Updated 2026-10-16 UTC. **Phase 1C backend onboarding is implemented, pushed and verified locally and in hosted CI.** Browser login/onboarding, client session coordination and recovery remain Phase 1D. No workflow/integration/AI work was started.

## Published increments and continuity

Initial local/fetched origin/master matched ccf94bb with a clean tree; existing branch/history/work/data were preserved. Foundation/auth checkpoints b1517ec, ffa151d, f00a1e0 and ccf94bb remain intact.

| Phase 1C increment | Actual publication evidence |
| --- | --- |
| 267645e — one-shot offline verified account | Push/ref verified; [CI passed](https://github.com/ChouaibSeishin/BridgeOps/actions/runs/38891771047) |
| 43338a8 — atomic organizations and tenant-bound Owner membership | Push/ref verified; [CI passed](https://github.com/ChouaibSeishin/BridgeOps/actions/runs/38893437874) |
| f12ce04 — delegated roles and concurrent last-Owner protection | Push/ref verified; [CI passed](https://github.com/ChouaibSeishin/BridgeOps/actions/runs/38894768689) |
| 7aec5ea — recipient-bound invitations and external SMTP adapter | Push/ref verified; [CI passed](https://github.com/ChouaibSeishin/BridgeOps/actions/runs/38898903235) |

## Implemented backend

- Existing BCrypt/opaque refresh/session rotation/replay controls, signed RS256 access, live family/account checks, cookie/CSRF/exact Origin protection, shared bounded throttling and default deny are retained.
- Explicit disabled-by-default non-web bootstrap, bounded secret stdin/no-echo console, fixed safe output, a locked consumed installation marker, one verified account and only an account-level organization.create grant. No tenant/default account is provisioned by normal startup.
- Authenticated organization create/list/detail, tenant member listing and read-only role catalog. Creation checks the live account grant and atomically writes the tenant, fixed role/permission templates, Owner membership and safe audit.
- Tenant-qualified reads/FKs, current permission resolution, revision-checked member role/status changes and soft removal, delegation ceilings, immutable HTTP role definitions and serialized last-active-Owner protection. Disabled accounts do not count as remaining Owners.
- Recipient-bound one-use invitations, 48-hour expiry, pending uniqueness, revocation, current issuer/grant revalidation, authenticated acceptance for existing accounts and token-guarded first-time enrollment. No existing password is reset and no account-level grant is inherited from tenant Owner status.
- A constrained migration-owned enrollment function rather than generic runtime users INSERT. It independently verifies raw proof, recipient, state/DB deadline, active issuer and tenant role ceiling before writing only the invited account/membership and consumption/events. Trusted search path, qualified relations and restricted EXECUTE; bootstrap/account-grant writes remain denied.
- Authenticated external SMTP with required TLS/hostname verification, finite timeouts, redacted provider failures and no plaintext/debug fallback. Defaults disabled. Tests use only a no-network sink/adapter; no real-recipient email was sent.

See [operator/API/configuration guide](development/PHASE_1C_ONBOARDING.md), [API outline](api/CONTRACT_OUTLINE.md), [database](architecture/DATABASE.md), [security](security/THREAT_MODEL.md) and [existing session policy](development/PHASE_1B_HTTP_AUTH.md).

## Fresh verification

| Check | Actual result |
| --- | --- |
| Bootstrap | Full run 53 passed before final quiet-command adjustment; final focused 3 CLI + 6 PostgreSQL cases passed; subsequent full runs/CI include the subprocess test |
| Organization boundary | Full 63 passed; final focused 9 cases passed using restricted runtime credentials, with separate operator migrations/fixtures |
| Membership protection | Focused 18 cases and full 72 tests passed; includes concurrent Owner demotions and audit rollback |
| Initial invitation run | All 33 restricted-runtime integration cases passed before final two cases were added |
| Final full backend verify | BUILD SUCCESS: 21 unit/MVC + 72 required PostgreSQL integration tests, 93 total, zero failures/errors/skips |
| Final security coverage | 35 tenant/member/invitation cases, four SMTP adapter cases, generic INSERT denial, raw-proof versus stored-hash enforcement, recipient/password takeover prevention, issuer revalidation, strict expiry, replay/concurrency, quotas, foreign IDs/FKs and transaction rollback |
| Docker/upgrade | Final up --build -d --wait passed; all three services healthy; V1–V7 installed on existing volume |
| Actual database boundary | Users/organizations/invitations counts all 0, installation unconsumed; runtime user INSERT/account-grant INSERT/marker UPDATE false, capability EXECUTE true, unrelated role EXECUTE false; capability owned by non-superuser bridgeops_migrator with trusted search path |
| Default controls | Actual default-disabled admin command exited 2; real SMTP disabled. No account/tenant/mail was created by verification |
| Frontend | Source unchanged; each published CI above reran four tests, typecheck/lint/build. No new browser/visual check or sign-in journey claimed |
| Documentation/secrets | File/link/fence checker and staged diff/whitespace/secret scan run at each commit gate; .env and generated signing keys remain untracked. No credentials printed or rotated; no volume deleted |

All test accounts/keys are disposable fixtures. Documentation checks do not render Mermaid; regex scanning is not proof every possible secret is absent. Exact commands are in the onboarding guide and Maven reports are generated in ignored backend/target.

## Configuration and remaining work

The Java 21 / Boot 4.1.1 / Maven Wrapper 3.9.16 / PostgreSQL 18.6 / Node 24.22.0 baseline is unchanged. Boot-managed mail starter is added for the user-selected SMTP requirement, without version overrides or new infrastructure services.

Before real invitations, an operator must configure authenticated TLS SMTP in the ignored environment. External provider credentials, connectivity and actual mailbox delivery were not available/tested. Synchronous SMTP acknowledgement is not transactional with PostgreSQL: a delivered-but-rolled-back message contains an unusable token; a test proves it cannot create an account. No durable outbox/retry guarantee.

The local workspace remains a status shell on localhost:8088 with no seeded application identity or tenant. Use the explicit offline command with your own metadata/secret input before manual API onboarding. HTTP bootstrap and general registration remain denied. Next: Phase 1D browser login/organization/member UI, in-memory access/serialized refresh and recovery/session settings, with real endpoint/browser tests.

No production security/load/TLS/HA, WCAG, backend vulnerability scan, backup/restore, RLS or compromised-runtime containment claim. Production must separate migration/operator execution and protect injected secrets; local Compose still supplies startup migration credentials. No license or verified private reporting channel is configured. Later workflow, integrations and AI/MCP remain pending.

## Phase 1D progress (2026-10-16 UTC)

1D.1 implements the real sign-in/protected shell and session coordinator. Fresh frontend typecheck, lint, 11 tests and production build passed. Origin-wide Web Locks serialize CSRF/cookie-sensitive sequences; no credential persistence/broadcast. Backend code is unchanged in this increment. Commit/push/hosted results are verified after the commit gate. Organization UI, recovery and browser end-to-end gates remain incomplete; Phase 1 is not complete. [Browser guide](development/PHASE_1D_BROWSER.md).

1D.1 published as 54a8c1f; push/ref verified and [hosted CI passed](https://github.com/ChouaibSeishin/BridgeOps/actions/runs/38907270990). 1D.2 adds the real organization/member/invitation UI and narrow capability/list reads; tests and publication are checked at its gate. Recovery and browser release checks remain pending.

1D.2 local verification: all 37 restricted-runtime organization integration cases passed (zero failures/errors/skips), including new live-capability and invitation-list tests. Frontend 14 tests, typecheck/lint and production build passed. No schema or runtime grant was expanded. Browser end-to-end verification remains a separate gate.
