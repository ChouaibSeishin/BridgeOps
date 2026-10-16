Historical Phase 1C counts: full verify passed 21 unit/MVC plus 72 required PostgreSQL integration tests (93 total). This includes offline bootstrap concurrency/rollback, 35 restricted-runtime organization/member/invitation cases and four no-network SMTP adapter cases. [Checkpoint evidence](PHASE_1C_ONBOARDING.md). These counts are historical, not the current suite size.

# Phase 1 verification

Phase 1D completed at 11aec6e with [successful hosted verification](https://github.com/ChouaibSeishin/BridgeOps/actions/runs/38931957774): 110 backend/integration/browser tests and 17 frontend tests. Onboarding, tenancy, browser sessions and recovery are implemented. Current executed outcomes are in [PROJECT_STATUS](../PROJECT_STATUS.md). The foundation commands/counts and earlier planned-gate statements below describe historical checkpoints. Use the current [README build order](../../README.md), including built frontend assets and installed Chromium before Maven verify.

## Historical foundation commands (superseded by README)

From repository root, use Java 21 and Node 24.22.0, and a Docker daemon accessible to the executing process:

```powershell
./backend/mvnw.cmd -f backend/pom.xml -B -ntp verify
npm.cmd --prefix frontend ci --no-audit --no-fund
npm.cmd --prefix frontend run typecheck
npm.cmd --prefix frontend run lint
npm.cmd --prefix frontend test
npm.cmd --prefix frontend run build
node scripts/verify-docs.mjs
./scripts/setup-local.ps1
./scripts/setup-auth.ps1
docker compose --env-file .env -f infra/compose.yaml config --quiet
docker compose --env-file .env -f infra/compose.yaml up --build -d --wait --wait-timeout 180
Invoke-RestMethod http://localhost:8088/api/v1/system/info
Invoke-RestMethod http://localhost:8088/health/liveness
Invoke-RestMethod http://localhost:8088/health/readiness
```

Run setup only when .env is absent. It intentionally refuses overwrite. POSIX backend equivalent: `cd backend` then `./mvnw -B -ntp verify`. Local sandbox permissions may require approved execution outside the sandbox; this is not evidence of an unavailable Docker daemon. Host runtime versions and container runtimes are reported separately.

Backend verify now runs fourteen unit/MVC tests (four foundation, two password/token, five JWT and three browser controls) and thirty-one required PostgreSQL integration tests (three foundation, ten session core and eighteen HTTP cases). Check reports for actual counts/failures/skips; missing Docker is a failure, not an accepted skip. Tests cover public info/correlation, denied unknown APIs, mutation denial, private docs by default, empty migration and minimal probes plus opt-in OpenAPI. HTTP tests additionally cover exact Origin/CSRF, signed session binding, production cookie attributes, live JWT revocation, bounded/concurrent throttling, replay/expiry, generic errors and dependency failure. See [the HTTP guide](PHASE_1B_HTTP_AUTH.md).

Frontend tests cover actual info/readiness requests, loading, recoverable outage, theme and routing. Typecheck and lint are separate gates. A production build is not a browser test; inspect desktop/mobile and both themes before describing visual quality.

## Historical foundation database/container acceptance

- Empty isolated test database migrates V1; query flyway_schema_history and technical metadata. Integration test container is disposable and separate from local Compose data.
- Production images build from locked dependencies; app containers run without root. Runtime DB role cannot create tables or mutate platform metadata; migration role can apply schema.
- Full Compose startup uses three services only, health dependencies, named PostgreSQL storage and localhost frontend binding. Browser requests use frontend origin.
- Stop PostgreSQL: liveness remains 200/UP; readiness returns 503 after bounded connection timeout. Restart it: readiness recovers; metadata and migration history persist. Run sequentially and record actual responses/timing. Restart the database after this test even when assertions fail.
- Unknown business APIs are denied and detailed management endpoints remain private. Responses/logs contain no generated credential values or stack traces for ordinary denials.
- Test restart persistence without deleting user volumes. Reset instructions are destructive and require explicit operator choice, not automatic verification cleanup.
- Opt-in docs override exposes backend localhost:8081 only; generated OpenAPI includes the implemented info route, no imaginary login/workflow APIs.
- CI pins official action commits, executes required checks and container smoke, and does not publish/deploy. Hosted CI status is unknown until actually run.

## Phase 1B–D security gates (completed checkpoint; retain as regressions)

Bootstrap: default-disabled/no web endpoint, explicit operator mode, secret not in argv/logs, one account only, serialized concurrent runs, repeat refusal, consumed installation marker, no implicit organization or platform privilege. Organization creation checks account-level grant and atomically creates Owner membership. Invites grant only authorized tenant roles.

Sessions: valid/invalid/expired/wrong-signature/issuer/audience cases; cookie flags and local-dev differences; exact Origin and CSRF checks on login/refresh/logout; single-use refresh/concurrent rotation/reuse family revocation; logout-all/password change/reset invalidate live access/refresh; brute-force limits; no secret leakage.

Tenancy: foreign organization/resource IDs fail across each resource family; permission denial; grant delegation ceilings; concurrent last-owner protection; safe audits. Browser login/onboarding uses real endpoints and memory-only access tokens, no localStorage refresh secrets.

## Later runtime invariants to test

Requester plus exactly one distinct eligible decision-maker; unique final decision; double submission/concurrent opposite decisions cannot resume twice. Deadline = created_at +24h including queue/retry/delay/approval waits; dispatch/resume and reconciliation reject expired work with DEADLINE_EXCEEDED. A test-run request missing effectsAcknowledged=true fails before creating work; UI confirmation clearly warns of real effects. These tests arrive with their implementation, not as empty skipped placeholders.

Report implemented, locally verified, automated-test verified, deployment verified, not verified and blocked separately. Capture command, UTC date, exit status and relevant reports. A documentation link check does not parse/render Mermaid or verify the application.

## Required browser gate (Phase 1D)

Build frontend assets, install the pinned Playwright Chromium runtime and then run full Maven verify, as described in [README](../../README.md). BrowserJourneyIT is required, not skipped when tooling is absent. It uses isolated PostgreSQL, explicit operator bootstrap, the real HTTP/security application, built React assets and test-only protected mail capture. Test sources/gateway/capture are excluded from the production jar. The host Node must be 24.22.0; BRIDGEOPS_TEST_NODE can select an absolute executable. Frontend tests/typecheck/lint/build and documentation/secret/container checks remain separate gates. Old foundation-only commands/counts above describe historical scope.

Final Phase 1D full local verify passed 110 tests (21 unit/MVC + 89 integration/browser), zero failures/errors/skips; frontend 17 cases and static/build/audit gates passed. Compose upgraded V1–V8 on the preserved existing volume and all three services were healthy. Users/organizations/reset proofs remain zero, SMTP disabled. Exact hosted result is recorded only after push in PROJECT_STATUS.md.
