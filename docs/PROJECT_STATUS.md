# Project status

Updated: 2026-10-15 UTC. Current increment: **Phase 1A — runnable technical foundation**. The documentation review is resolved and implementation is authorized; no additional Phase 0 approval is pending. Full Phase 1 (authentication, bootstrap and tenant onboarding) is **not complete**.

## Implemented

- Java 21/Spring Boot 4.1.1 backend, Maven Wrapper 3.9.16 with verified distribution checksum, DTO-based safe system info and server-generated correlation IDs.
- Default-denied business routes, no generated account/password, minimal separate liveness/readiness, opt-in OpenAPI and structured logs. This is not implemented user authentication.
- PostgreSQL 18.6 and Flyway V1 technical platform_metadata migration; distinct migration/runtime database roles in Compose. Business schema remains planned.
- Strict React/TypeScript/Vite frontend with TanStack Query, Overview and Runtime routes, actual info/readiness requests, loading/error/retry states, theme switching and responsive navigation. No fabricated tenant/workflow statistics or login controls.
- Digest-pinned multi-stage Docker builds, non-root app containers, same-origin nginx routing, named PostgreSQL volume, unique generated local credentials, ignored .env and localhost-only frontend binding.
- Four backend MVC boundary tests, three required PostgreSQL/OpenAPI integration tests, four frontend behavior tests, lockfile and pinned GitHub Actions workflow.
- Conflicts A–G documented, revised seven-phase roadmap, explicit stack and bootstrap ADRs, accurate reporting-channel policy, real-effects test-run confirmation contract and inclusive deadline semantics.

## Verification evidence

| Category | Command/check actually executed | Result |
| --- | --- | --- |
| Documentation | project-local Node scripts/verify-docs.mjs | Passed: 35 Markdown files, 60 local links, 6 Mermaid blocks, 14 ADRs |
| Backend build/tests | backend/mvnw.cmd -f backend/pom.xml -B -ntp with project-local Maven cache, verify | BUILD SUCCESS; 4 MVC tests + 3 required PostgreSQL IT tests, zero failures/errors/skips |
| Database integration | Testcontainers PostgreSQL 18.6, Flyway empty-schema migration | V1 applied; metadata and schema history assertions passed |
| OpenAPI | Integration test with explicit docs-enabled property | Implemented info route present; imaginary login route absent; private-by-default MVC test passed |
| Frontend | pinned npm install; typecheck; lint; test; build | Passed; 4 behavior tests; production Vite build completed |
| Production dependency audit | npm --prefix frontend audit --omit=dev --audit-level=high | Passed, reported zero vulnerabilities at check time; not a comprehensive backend/dev-dependency security audit |
| Compose | config --quiet; images built; startup on port 8088 | Three services healthy; final documented up --build -d --wait command passed on port 8088 |
| API/probes | GET info, liveness, readiness via http://localhost:8088 | 200; identity matches DTO; both probes UP with minimal output |
| Denied API | GET /api/v1/organizations | 401 safe denial; no fabricated organization endpoint |
| Database privilege check | pg_roles + has_schema_privilege + has_table_privilege | Runtime role: not superuser, cannot CREATE in public, cannot INSERT platform_metadata |
| Outage/recovery | Stop only BridgeOps PostgreSQL, check probes, restart in finally | Liveness 200; readiness 503 during outage; readiness 200 after restart; technical data persisted |
| Actual browser | In-app browser desktop light/dark, 390x844 mobile, theme control and Runtime navigation | Inspected; mobile document width 390 equals viewport 390; captured console warnings/errors empty |
| Screenshots | Captured from actual running local browser | docs/screenshots/foundation-desktop.jpg and foundation-mobile-dark.jpg; no mock screenshots |
| Limited secret leakage | Generated local database passwords checked against Git-visible source and captured backend/PostgreSQL logs, values never printed | Passed; this is not a comprehensive secret scanner |
| Git hygiene | git diff --check | Exited successfully; files remain untracked in this initially empty repository, so this alone does not check new-file whitespace |

Initial verification failures were corrected: PowerShell Maven argument splitting; missing checksum endpoint (used official SHA-512 plus derived SHA-256); MVC slice missing explicit web security; Testcontainers digest compatibility; builder missing unzip causing archive-format checksum mismatch. Windows rejected port 8080; the project default and current .env now use localhost:8088. No unrelated running service was stopped. A documentation replacement bug damaged four texts; the link checker exposed it, originals were restored, and explicit corrections/checks passed.

## Runtime and dependency observations

Observed again in this session: host Java 21.0.4/Maven 3.9.9/Node 20.17.0. Approved execution outside the sandbox reached Docker Engine 28.3.2 and Compose 2.39.1; the earlier denied daemon observation was sandbox-specific, not an unavailable engine. Project commands use checksum-verified local Node 24.22.0/npm 11.19.1. The pinned runtime image reports Temurin 21.0.12.1+1-LTS; nginx reports 1.30.6. Exact image digests/checksums and dependency versions are in [dependency evidence](development/DEPENDENCY_LOCK.md).

## Current source/module structure

Only shared technical backend configuration/web code and the frontend foundation exist. Planned domain modules remain identity, organizations, integrations, workflows, executions, approvals, ai, mcp, audit and notifications. See [repository structure](development/REPOSITORY.md). No TODO controllers or fake module implementations stand in for these features.

## Commands and local review

```powershell
docker compose --env-file .env -f infra/compose.yaml ps
Invoke-RestMethod http://localhost:8088/api/v1/system/info
Invoke-RestMethod http://localhost:8088/health/readiness
```

The existing workspace .env was generated with unique local-only secrets; do not rerun setup to overwrite it. A clean checkout uses scripts/setup-local.ps1 first. Build/test/start/stop/reset/docs instructions are in [README](../README.md) and the [Phase 1 verification plan](development/PHASE_1_VERIFICATION.md).

## Not yet implemented or verified

Authentication/session rotation/reuse/revocation, one-shot bootstrap, organization creation/membership/invites and tenant authorization are planned Phase 1B/C. No owner account or working login exists. Workflow definitions/execution/approvals are Phases 2–4; full integrations/webhooks Phase 5; AI/MCP Phase 6; polish/delivery Phase 7. Full business vertical slice is not complete.

Mermaid was not parsed/rendered; link/fence checks establish only document structure. No WCAG conformance, assistive-technology audit, full secret scanner, backend vulnerability scan, hosted GitHub Actions result, cloud deployment, production TLS, backup/restore or production security claim. The optional docs-port override is configured; exact-pair OpenAPI is integration-tested, not a complete Swagger browser walkthrough.

## Next increment

Phase 1B: identity/session migrations and business services, BCrypt, validated signed access tokens, rotating hashed refresh tokens, family revocation/reuse, exact Origin/CSRF protection, bounded login throttling and security tests. Then Phase 1C implements the documented offline initial-account lifecycle and tenant onboarding. Preserve all reviewed boundaries; do not call Phase 1 complete until these acceptance gates pass.

## GitHub development checkpoint — 2026-10-15

The user confirmed https://github.com/ChouaibSeishin/BridgeOps as the public development repository. Initial inspection found local master with no commits/remotes/tracked files and an empty GitHub repository. Foundation checks were repeated successfully (7 backend tests, 4 frontend tests, typecheck/lint/build); Gitleaks 8.30.1 found no leaks in the isolated 81-file publication candidate snapshot. Exact commit and push outcomes are verified separately, never inferred from the URL. The configured branch and Git identity are preserved. See the Git workflow and assessment in docs/reviews.

Phase 1B is split into verifiable increments: first internal identity/refresh-session persistence and replay/concurrency tests, then signed access tokens and HTTP/CSRF/Origin/rate-limit behavior. Browser login and bootstrap remain unavailable until their complete security boundary exists.
