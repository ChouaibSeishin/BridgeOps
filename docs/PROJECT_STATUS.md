# Project status

Updated: 2026-10-15 UTC. Current increment: **Phase 1B.1 — internal identity and refresh-session core**. Phase 1A is preserved and verified. Full Phase 1B HTTP authentication and Phase 1C bootstrap/tenant onboarding are **not complete**.

## GitHub and completed baseline

Public repository: [ChouaibSeishin/BridgeOps](https://github.com/ChouaibSeishin/BridgeOps). Local branch remains master and tracks origin/master. Initial inspection found no local commits/remotes/tracked files and no GitHub refs; existing work was preserved, secret-checked and committed.

Foundation commit: `b1517ec` — `chore: establish verified BridgeOps foundation`. Push succeeded; GitHub's master ref matched the commit hash. The [foundation GitHub Actions run](https://github.com/ChouaibSeishin/BridgeOps/actions/runs/38776342374) completed successfully. Git Credential Manager worked; GitHub CLI/connector installation was unnecessary. Do not infer the outcome for later commits from that baseline run.

## Implemented now

- Phase 1A Spring Boot/React/PostgreSQL foundation, info/health endpoints, default-denied business routes, real status shell, same-origin proxy, non-root app containers, pinned dependency builds and CI.
- V2 users/session_families/refresh_sessions/identity_security_events migration, JPA ownership and constraints, current-user/family binding and safe internal security events. No user is seeded.
- BCrypt cost 12 with minimum 12 Unicode code points and maximum 72 UTF-8 bytes; dummy verification for unknown accounts.
- 256-bit opaque refresh credentials stored only as SHA-256 hashes; seven-day absolute family expiry; single-use rotation, strict replay revocation, individual/all-family revocation, disabled-account checks and safe redacted outcomes.
- Account-before-family transaction locks, scalar lookup before locking to avoid stale managed state, and a typed rejection so replay revocation/audit commits rather than rolls back.
- Git workflow/assessment, secure archive/key/export ignores, a canonical dependency-lock pointer and a data-preserving local database password-rotation guide.

Internal session services confer no organization/platform permissions and have no HTTP mapping. The existing deny-by-default SecurityFilterChain remains unchanged. There is no working login UI, access JWT, refresh cookie, public registration, bootstrap HTTP endpoint or initial-owner command yet.

## Verification executed in this session

| Check | Actual result |
| --- | --- |
| Baseline backend verify | 4 MVC + 3 required PostgreSQL tests passed before the foundation commit |
| Frontend typecheck, lint, test, build | Passed; 4 behavior tests. Frontend source is unchanged by the session-core increment |
| Narrow password/token tests | 2 passed before full verification |
| Final backend verify | BUILD SUCCESS; 6 unit/MVC + 13 required PostgreSQL integration tests, zero failures/errors/skips |
| Session core cases | Bad/unknown/disabled credentials; rotation; fixed expiry; persisted replay revocation; exact expiry; owned/global revocation; account suspension; malformed tokens; concurrent duplicate refresh; email constraints |
| Compose build/start | Final up --build -d --wait passed; all three services healthy on localhost:8088 |
| Existing-volume upgrade | V1 and V2 present; users count 0; no fixture or default account seeded |
| Runtime privileges | Can INSERT/UPDATE refresh_sessions and INSERT security events; cannot UPDATE/DELETE identity_security_events |
| Unexposed auth API | GET /api/v1/auth/me returns 401; no partial transport exposed |
| Gitleaks 8.30.1 | Official checksum-verified binary; publication candidates/staged baseline/history and new backend source scans reported no leaks; final staged gate is required before each commit |
| Ignored credentials | .env never staged/tracked; initial history was empty; local tools/caches/archives/keys/exports excluded |

Documentation links and actual staged diff/whitespace are checked by the final commit gate. The precise second commit/push and its CI result must be verified from Git/GitHub, not guessed inside a document prepared before commit creation. See [the session-core scope and commands](development/PHASE_1B_SESSION_CORE.md), [Git workflow](development/GIT_WORKFLOW.md) and [repository assessment](reviews/2026-10-15_REPOSITORY_ASSESSMENT.md).

Prior Phase 1A evidence remains valid as historical verification: desktop/mobile light/dark inspection, zero captured console warnings/errors, no 390px overflow, minimal probes, database outage liveness 200/readiness 503/recovery 200, persistent technical data and safe connection errors. Screenshots are actual foundation captures; no new UI behavior is claimed for this backend increment.

## Current configuration and source

Java 21 compile target; Boot 4.1.1; Maven Wrapper 3.9.16; managed Jupiter 6.0.3; PostgreSQL 18.6; project-local Node 24.22.0. Host Java remains 21.0.4, while the pinned container reports maintained Temurin 21.0.12.1. No framework versions were upgraded. [Dependency evidence](development/DEPENDENCY_LOCK.md) and [repository structure](development/REPOSITORY.md) remain authoritative.

Domain code now includes identity/application, identity/domain and identity/persistence plus a shared Clock bean. Other business modules remain planned. All build output, local tools and .env remain ignored. Local credentials were not automatically rotated; the [operator guide](security/LOCAL_CREDENTIAL_ROTATION.md) explains actual role-password rotation without deleting data. Treat archive values as potentially disclosed if shared.

```powershell
docker compose --env-file .env -f infra/compose.yaml ps
Invoke-RestMethod http://localhost:8088/health/readiness
./backend/mvnw.cmd -f backend/pom.xml -B -ntp verify
```

## Not implemented or not verified

HTTP login/refresh/logout, signed access validation, cookies, exact Origin/CSRF enforcement, throttling and browser session coordination remain Phase 1B.2. The current strict replay policy requires eventual cross-tab/client serialization of refresh. Bootstrap/organizations/invitations/permission delegation/tenant isolation are Phase 1C. Workflows/approvals, integrations/webhooks, AI/MCP and delivery follow the established roadmap.

No production security, WCAG/assistive-technology conformance, cloud deployment/TLS, backend vulnerability scan or backup/restore claim. Mermaid source was not parsed/rendered. A regex secret scanner is not proof that all possible secrets are absent. No license or verified private vulnerability-reporting channel has been configured. Local Compose supplies migration credentials to the backend; production must separate migration execution and omit those credentials from runtime. No database volume or unrelated service was deleted/stopped.

## Next increment

Phase 1B.2: signed access tokens bound to live families, complete HTTP DTO/controller/application integration, same-origin/CSRF protection, correct local/production cookie flags, bounded login throttling, safe errors and negative HTTP security tests. Then Phase 1C implements offline one-shot initial-account provisioning and tenant onboarding. Commit and push each completed verified increment separately.
