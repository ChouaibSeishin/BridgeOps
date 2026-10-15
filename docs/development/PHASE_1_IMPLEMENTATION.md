# Phase 1 implementation sequence

The documentation review authorizes this implementation. Phase 1 now includes both the original technical foundation and original authentication/multi-tenancy phase. Subsequent numbering follows the revised seven-phase backlog.

## 1A — Runnable technical foundation (current increment)

Create backend/pom.xml, Maven Wrapper, BridgeOpsApplication, shared security/error configuration, SystemController, V1 technical metadata migration, application.yml, unit and required PostgreSQL integration tests. Create frontend package/lock/config, React shell with Overview and Runtime routes, real API status/loading/error/retry behavior, retained design tokens, responsive styling and RTL tests. Add Dockerfiles, same-origin nginx proxy, infra/compose.yaml, .env.example, setup/verification scripts, pinned CI and exact README commands.

Dependencies: consistent decisions A–G and locked released toolchain. Acceptance: both builds; typecheck/lint/tests; empty PostgreSQL Flyway migration; container build/start; real frontend/backend request; readiness failure/recovery; unknown business paths denied. No login screen, organization statistics or member records are fabricated before those APIs exist.

## 1B — Identity and session vertical slice

Add identity application/domain/persistence/web packages; users, session families, hashed refresh tokens, account grants, rate-limit buckets and security audit migrations. Implement login, refresh rotation/reuse, logout/logout-all and current-user DTO; CSRF bootstrap/exact Origin rules; BCrypt; token signing/validation and live revocation checks. Offline bootstrap command uses password from stdin or mounted secret, never CLI arguments/logs. Integration tests cover valid/invalid login, expiry, issuer/audience, CSRF, repeated and concurrent refresh, logout revocation and rate limits.

## 1C — Initial owner and tenant boundary

Implement consumed installation marker and account-level organization.create grant; authenticated create/list organization and Owner membership in one transaction. Invite acceptance is recipient-bound, single-use and delegated-role checked. Add tenant-qualified repositories/composite foreign keys, permission tests, last-owner protection and safe audits. UI provides real login, organization creation/selection and membership display. Test duplicate bootstrap, concurrent bootstrap, production/default disabled mode, cross-tenant access and invitation privilege boundaries.

## 1D — Phase 1 completion

Finish session settings/recovery according to the phase backlog; persist real dashboard counts only for implemented entities; add browser login/onboarding smoke, negative tenant checks, exact startup/bootstrap guide and CI evidence. Phase 1 is incomplete until the auth/membership acceptance gates pass.

## First business vertical slice after Phase 1

Phase 2 saves/validates/publishes a small manual-trigger -> synthetic-action -> approval -> action -> end definition using basic forms. Phase 3 executes only supported deterministic synthetic capabilities with durable state, idempotency, retry and fencing. Phase 4 adds one distinct approver and safe resume, completing the requested business demonstration before external connectors/AI. Sensitive actions stop safely until approval support exists. Phase 5 expands integrations/webhooks, Phase 6 AI/MCP, Phase 7 polish/delivery. Do not present the foundation as the finished workflow product.

## Proposed implementation tree

```text
backend/
  pom.xml, mvnw, mvnw.cmd, .mvn/wrapper/
  src/main/java/com/bridgeops/BridgeOpsApplication.java
  src/main/java/com/bridgeops/shared/{configuration,web}/
  src/main/resources/application.yml
  src/main/resources/db/migration/V1__platform_foundation.sql
  src/test/java/com/bridgeops/
frontend/
  package.json, package-lock.json, tsconfig.json, vite.config.ts
  src/app/, src/lib/, src/styles/
  src/main.tsx, src/test/
infra/compose.yaml
infra/docker/{backend.Dockerfile,frontend.Dockerfile,nginx.conf}
scripts/{verify-docs.mjs,setup-local.ps1}
.github/workflows/ci.yml
.env.example
```

Phase 1B/C add only actual module files, not empty placeholder controllers. Each increment reports exact commands and results in PROJECT_STATUS.md.
