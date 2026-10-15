Historical checkpoint: this report describes commit ffa151d. The current HTTP adapter is documented in [Phase 1B.2](PHASE_1B_HTTP_AUTH.md); its implementation supersedes the pending-transport statements below.

# Phase 1B.1 — identity and opaque-session core

Scope: internal application services and PostgreSQL persistence only. This increment does not expose login/refresh/logout HTTP routes, issue access JWTs, provide cookies/CSRF, throttle callers, create an initial account or add a sign-in UI. Those remain explicit next increments. The existing deny-by-default HTTP security chain is unchanged.

## Files and behavior

- V2__identity_sessions.sql adds users, session_families, refresh_sessions and identity_security_events. No default/demo account is inserted. Users have a unique normalized ASCII email; database checks prevent a forged canonical value. Full invitation/verification/account-grant fields arrive with onboarding migrations.
- identity/persistence owns JPA entities and repositories. Composite refresh foreign keys bind expiry to the family and bind each parent to its family; parent uniqueness permits at most one successor.
- AccountPasswords enforces BCrypt cost 12, minimum 12 Unicode code points and maximum 72 UTF-8 bytes, avoiding silent BCrypt truncation. Unknown-user checks perform a dummy BCrypt verification. HTTP enumeration/rate-limit defenses are not claimed until their adapter is tested.
- RefreshTokens generates 32 random bytes (256 bits), URL-safe encodes them and stores only SHA-256 hashes. Raw credentials exist only in the returned internal grant; its diagnostic string is redacted. Transport must later place refresh credentials in the approved HttpOnly cookie, never a JSON field/localStorage.
- PasswordSessionService authenticates a known active account internally, starts a seven-day absolute family, rotates a single-use credential, rejects expired/revoked/inactive sessions, revokes all or an owned family, and checks live family/account state. It does not confer organization or platform privileges.
- Security events record safe type/user/family/time only. Runtime privileges permit insertion/read but deny update/delete of event rows. This is access control, not tamper evidence.

## Concurrency and transaction decisions

Each mutating operation takes the account row lock before a family lock. This serializes rotation with per-family/all-family revocation for that account. Transaction timeout is ten seconds (five for live-read checks). A non-entity lookup projection identifies the account/family before locking; loading managed credential/family entities too early could cache stale state across the wait.

Rotation consumes the old credential and inserts its successor atomically. Reusing a consumed credential revokes the family and records REFRESH_REUSE_DETECTED. Rejection is a typed return value, not an exception that would roll back those changes. Two simultaneous requests therefore yield one successor followed by strict replay revocation; the issued successor becomes unusable. This is the reviewed strict policy, not a concurrency grace window. The eventual client must serialize refresh calls.

The absolute family expiry never extends through rotation. Expiry is checked with an injected Clock, including the exact boundary. Consumed hashes remain available for replay detection; no cleanup job is introduced before its retention/security policy exists. Internal revoke methods require an already authenticated user identity from the future adapter; they must never receive a body-provided user ID as authority.

## Verification commands

```powershell
./backend/mvnw.cmd -f backend/pom.xml -B -ntp "-Dtest=PasswordPolicyTest" test
./backend/mvnw.cmd -f backend/pom.xml -B -ntp verify
docker compose --env-file .env -f infra/compose.yaml up --build -d --wait --wait-timeout 180
```

Tests exercise password byte limits, rejected credentials and inactive accounts, rotation, fixed expiry, replay revocation with a persisted event, exact expiry boundary, logout-all, family ownership, idempotent revocation, account suspension, malformed tokens, concurrent duplicate refresh and database normalization/uniqueness constraints. PostgreSQL tests use a separate disposable Testcontainers instance and synthetic accounts. They do not create a local owner or change existing database credentials.

Actual check counts, commit/push and Compose results are recorded in PROJECT_STATUS.md. These internal-service checks do not verify HTTP authentication, browser cookies or tenant isolation. No framework dependency or existing permission policy was upgraded.
