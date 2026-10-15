# Repository structure

Phase 1A contains actual backend/frontend source, Maven Wrapper, npm lock, technical migration, tests, Dockerfiles, Compose and CI. Domain modules below remain planned until their increment; do not mistake architecture diagrams for implementation.

```text
backend/
  pom.xml, mvnw, mvnw.cmd, .mvn/wrapper/
  src/main/java/com/bridgeops/
    BridgeOpsApplication.java
    shared/configuration/SecurityConfiguration.java
    shared/web/{SystemController,CorrelationFilter,ApiErrors}.java
  src/main/resources/application.yml
  src/main/resources/db/migration/V1__platform_foundation.sql
  src/test/java/com/bridgeops/{SystemApiTest,FoundationIT}.java
frontend/
  package.json, package-lock.json, tsconfig.json, vite.config.ts
  eslint.config.js, index.html
  src/app/{App.tsx,App.test.tsx}
  src/lib/api.ts
  src/styles/{tokens.css,app.css}
  src/test/setup.ts
  src/main.tsx
infra/
  compose.yaml, compose.docs.yaml
  docker/{backend.Dockerfile,frontend.Dockerfile,nginx.conf,init-database.sh}
scripts/{verify-docs.mjs,setup-local.ps1}
.github/workflows/ci.yml
docs/{product,architecture,api,security,development,design}/
.env.example, .gitignore, .dockerignore, .gitattributes, .editorconfig
README.md, SECURITY.md
```

Implemented addition in Phase 1B.1: identity/application (password/token/session services), identity/domain (typed/redacted outcomes), identity/persistence (entities and repositories), ClockConfiguration, V2__identity_sessions.sql, PasswordPolicyTest and PasswordSessionIT. Phase 1B.2 adds identity/web (HTTP DTOs/controller, security configuration/filters, signed CSRF and cookies), AccessTokens, IdentityQueries, AuthenticationThrottle, V3__authentication_throttles.sql, AccessTokensTest, BrowserSecurityTest, AuthenticationHttpIT and scripts/setup-auth.ps1. An account-provisioning command does not exist yet.

Planned modules: organizations, integrations, workflows, executions, approvals, ai, mcp, audit and notifications. Each module exposes `api` contracts and owns application/domain/persistence/web code when useful; no unused package skeletons or interface-per-class requirement. REST DTOs do not expose JPA entities. Cross-module entities/repositories are forbidden; use public application services.

Flyway versions are globally ordered with owning-module descriptions. Tests mirror business packages; `*Test` runs in the unit/MVC phase, `*IT` in required Maven failsafe verify. Frontend feature tests are colocated; the full browser journey arrives with identity/workflow behavior. Source imports semantic tokens rather than inventing per-page colors.

Local .tools/.cache/.tmp and generated .env are ignored, not part of a clean checkout. Build output, node_modules and local secrets are excluded from Git and Docker context. Root .env.example contains empty placeholders only. Repository root is already BridgeOps; do not nest a duplicate project.
