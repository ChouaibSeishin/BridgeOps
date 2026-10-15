# BridgeOps

A modular Java/Spring Boot and React operations platform for governed workflow automation.

**Current increment: Phase 1A technical foundation.** Backend, frontend, PostgreSQL migration, health/info API, live status shell and container setup are implemented. Authentication, first-account provisioning, tenant membership, workflows, approvals, integrations, AI and MCP are subsequent increments; they are not represented by fake controls or statistics. See [current verification evidence](docs/PROJECT_STATUS.md).

The [conflict-resolution report](docs/CONFLICT_RESOLUTION.md) records the reviewed decisions. The [implementation sequence](docs/development/PHASE_1_IMPLEMENTATION.md), [technology lock](docs/development/TECHNOLOGY.md) and [backlog](docs/product/BACKLOG.md) govern the next work. The [master specification](docs/product/MASTER_PROJECT_PROMPT.md) is retained with explicit amendments.

## Start locally

Prerequisites: Docker Engine/Desktop with Compose 2.24+ and PowerShell. Host Java/Node are unnecessary for the full container startup. Commands below run from the repository root.

```powershell
./scripts/setup-local.ps1
docker compose --env-file .env -f infra/compose.yaml config --quiet
docker compose --env-file .env -f infra/compose.yaml up --build -d --wait --wait-timeout 180
```

Open [the local workspace](http://localhost:8088). Setup generates unique local database secrets in ignored .env and refuses to overwrite an existing file. .env.example contains empty placeholders only. Change BRIDGEOPS_PORT in .env if 8088 is occupied; adjust URLs accordingly. Bindings are localhost-only; only the frontend is published. No paid AI key is required.

Port 8088 is the default because this Windows host rejected binding to 8080. No existing service was stopped to claim that port.

The first PostgreSQL startup creates distinct migration/runtime roles. Flyway applies V1__platform_foundation.sql using the migration role; the runtime role has read-only access to foundation tables. Future business migrations grant only required DML. Existing database volumes retain their role passwords: changing .env does not rotate existing database roles.

## Verify and operate

```powershell
Invoke-RestMethod http://localhost:8088/api/v1/system/info
Invoke-RestMethod http://localhost:8088/health/liveness
Invoke-RestMethod http://localhost:8088/health/readiness
docker compose --env-file .env -f infra/compose.yaml ps
docker compose --env-file .env -f infra/compose.yaml logs --tail 100 backend
docker compose --env-file .env -f infra/compose.yaml down
```

Liveness checks the application process; readiness includes PostgreSQL. Public probe output is minimal. `/api/v1/system/info` returns `{name,apiVersion}`. All other business routes are denied. The browser uses relative URLs through a same-origin proxy; it never receives internal container hostnames or secrets. Named database volumes survive `down`. Rebuild using `up --build -d --wait` after source changes.

To reset **disposable development data only**, stop services and explicitly run `docker compose --env-file .env -f infra/compose.yaml down --volumes`. This deletes this Compose project's database volume; never use it for data you need to keep. Normal verification does not delete existing volumes.

## Builds and tests

Use Java 21 and Maven Wrapper for backend commands. Integration tests require a working Docker daemon; they fail rather than silently skip when it is missing.

```powershell
./backend/mvnw.cmd -f backend/pom.xml -B -ntp verify
```

On Linux/macOS install unzip and a SHA-256 tool, then use `cd backend` and `./mvnw -B -ntp verify`. The wrapper checksum targets the ZIP; without unzip this wrapper switches archive formats. `test` runs MVC/security contract tests only; `verify` also runs PostgreSQL/Flyway/OpenAPI integration tests. Container builds skip tests because CI runs them separately; an image build alone is not test evidence.

Frontend requires Node **24.22.0** (global 20.17.0 is incompatible). With that runtime on PATH:

```powershell
npm.cmd --prefix frontend ci --no-audit --no-fund
npm.cmd --prefix frontend run typecheck
npm.cmd --prefix frontend run lint
npm.cmd --prefix frontend test
npm.cmd --prefix frontend run build
node scripts/verify-docs.mjs
```

The current workspace also has a checksum-verified untracked runtime in `.tools/node-v24.22.0-win-x64`; it is not part of a clean checkout. To use it in this workspace:

```powershell
$env:PATH = (Join-Path $PWD '.tools/node-v24.22.0-win-x64') + ';' + $env:PATH
```

A fresh checkout may instead use the official Node 24.22.0 installer or the pinned Node build image. Lockfiles and wrapper distribution/checksum make dependency resolution reproducible. The [verification plan](docs/development/PHASE_1_VERIFICATION.md) lists failure and security checks. CI configuration is committed; a hosted CI run is not implied.

## API documentation and frontend development

Public Compose routing hides management and OpenAPI paths by default. To opt in locally:

```powershell
docker compose --env-file .env -f infra/compose.yaml -f infra/compose.docs.yaml up -d --wait
```

Open [Swagger UI](http://localhost:8081/swagger-ui.html) or [OpenAPI JSON](http://localhost:8081/v3/api-docs). The override exposes the backend only on localhost:8081 and enables docs; omit it for ordinary startup. Documentation describes implemented routes only. `npm.cmd --prefix frontend run dev` serves the development client, proxying API/health requests to that backend on 8081. Keep the core Compose services running. No production TLS deployment is performed by this setup.

## Bootstrap and security boundary

The chosen design provisions one initial account through an explicit offline, one-shot command. That account logs in and creates its organization/Owner membership transactionally; subsequent users join by invitation. **This command and login are not implemented in Phase 1A.** There are no seed credentials, privileged HTTP bootstrap endpoint, production default account or working sign-in controls. Phase 1B/C implements and tests the [bootstrap lifecycle](docs/architecture/adr/0014-offline-bootstrap.md) and [session strategy](docs/architecture/adr/0002-browser-sessions.md).

No private security-reporting method currently exists; [SECURITY.md](SECURITY.md) describes the public-release gate. No WCAG, production security, hosted deployment or workflow correctness claim is made for this foundation.

## Architecture and product documents

- [Requirements and scope](docs/product/REQUIREMENTS.md)
- [System/module architecture and component diagram](docs/architecture/SYSTEM.md)
- [Execution/state/approval sequence](docs/architecture/EXECUTION.md)
- [Database relationships](docs/architecture/DATABASE.md)
- [Threat model and permissions](docs/security/THREAT_MODEL.md)
- [Implemented versus planned API contract](docs/api/CONTRACT_OUTLINE.md)
- [Design tokens and layout direction](docs/design/FOUNDATION.md)
- [ADRs](docs/architecture/adr/README.md)
- [Repository structure](docs/development/REPOSITORY.md)

Next: Phase 1B identity/session behavior, then Phase 1C bootstrap/organizations and tenant isolation. The first workflow/approval business demonstration follows Phases 2–4; full integrations/webhooks, AI/MCP and delivery polish remain in the revised roadmap.

## Actual foundation preview

Captured from the running local application, not a mockup. [Mobile dark-theme capture](docs/screenshots/foundation-mobile-dark.jpg). These captures do not establish accessibility conformance.

![Running Phase 1A workspace](docs/screenshots/foundation-desktop.jpg)

## Incremental GitHub development

The public development repository is [ChouaibSeishin/BridgeOps](https://github.com/ChouaibSeishin/BridgeOps). Publication status is recorded in PROJECT_STATUS.md; a URL alone does not prove a push succeeded. Follow the [Git workflow](docs/development/GIT_WORKFLOW.md) and [local credential rotation guide](docs/security/LOCAL_CREDENTIAL_ROTATION.md). No license has been selected yet.
