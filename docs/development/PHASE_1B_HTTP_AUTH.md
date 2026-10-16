Historical Phase 1B.2 checkpoint: signature/cookie/CSRF policy below remains applicable. New organization/member/invitation routes and current completion evidence are in [the Phase 1C guide](PHASE_1C_ONBOARDING.md) and PROJECT_STATUS.md; the old statements about all business routes being denied describe that earlier checkpoint.

# Phase 1B.2 — HTTP authentication

## Scope and checkpoints

Commit f00a1e0 introduced the isolated AccessTokens component after a fresh 24-test backend verify (11 unit/MVC and 13 PostgreSQL integration tests). The current HTTP increment integrates that component with the existing session core. No account is seeded. First-account provisioning, password setup/reset, invitations, tenant grants, browser sign-in and refresh coordination remain separate work. Every unrelated business route stays denied, including for authenticated callers.

## Implemented API

All routes below are relative to /api/v1/auth. Responses and errors use Cache-Control: no-store. No JSESSIONID or server-side browser session is used.

| Route | Requirements and response |
| --- | --- |
| GET /csrf | Bootstrap a signed CSRF cookie; returns {headerName,token}. A supplied Origin must exactly match. Cross-site Fetch Metadata is rejected. The browser same-origin policy prevents another origin reading this response; CORS is disabled. |
| POST /login | JSON {email,password}; one exact Origin header and signed CSRF cookie plus matching X-CSRF-TOKEN header. Returns {accessToken,tokenType,expiresAt,user:{id,email,displayName}}. Refresh and replacement CSRF credentials go only to cookies. |
| POST /refresh | Origin and CSRF as above, plus the refresh cookie. Rotates the refresh and CSRF credentials; returns the same access DTO. Omit the Authorization header on this cookie-authorized route. |
| POST /logout | Origin and CSRF; revokes the family addressed by the refresh cookie without issuing a successor, clears both cookies, returns 204. An absent/unknown refresh value is harmless and still clears cookies. |
| POST /logout-all | Origin, CSRF and a live bearer credential; revokes all families for the authenticated user, clears this browser's cookies, returns 204. No body-provided identity is accepted. |
| GET /me | Live bearer credential; returns {user:{id,email,displayName},familyId,accessExpiresAt}. Refresh cookies alone cannot authenticate it. |

Invalid credentials, unknown accounts and disabled accounts share a 401 code/message. Malformed/unknown login fields return a safe 400. Origin/CSRF failures return 403 before session mutation. Quota denial returns 429 with Retry-After seconds. PostgreSQL errors at the HTTP boundary return a generic 503 and never grant access. Unauthenticated protected routes return 401; valid identity with no allowed business route receives 403. Missing public registration/bootstrap routes remain unavailable. Do not return or log internal Granted values.

## Signing and live authorization

Spring Security JOSE and Nimbus are managed by the unchanged Boot 4.1.1 BOM. RS256 is the only accepted algorithm. RSA keys must have at least 2048 bits (local setup generates 3072). Required claims are sub, sid, iss, aud, iat, exp and jti. Audience comparison is exact; identity claims contain no authoritative role/tenant grants. The ten-minute expiry is capped by the absolute refresh-family deadline. Validation requires the expected type/key ID, signature, all claims, UUID identifiers, no future issuance and strict expiry with no grace interval. Token-supplied URLs never cause key lookup.

Every bearer-authenticated request checks current family ownership, expiry, revocation and account status in PostgreSQL. Replay revocation therefore invalidates previously issued access credentials. The existing account-before-family locks and typed replay rejection remain intact. Duplicate refresh yields one success followed by strict family revocation; the browser client must eventually serialize refresh across tabs. Do not send expired access credentials to cookie-authorized refresh/login endpoints.

One previous public key can be configured with a fixed acceptance deadline at most ten minutes after configuration. Coordinate a cutover to the new signing pair and key ID with the old public key/ID and deadline. Avoid mixed-key replicas unless every verifier is first configured to recognize both IDs within the same bounded deployment window. After that absolute deadline, remove the old verification configuration. Keep old private material outside runtime. Never reuse the active ID for a different key. An unchanged deadline cannot extend overlap through restart. All replicas must share the configured CSRF key. CSRF-key replacement deliberately invalidates existing CSRF cookies; clients can bootstrap again. No key values belong in Git, .env.example, logs or chat.

## Browser controls

Production refresh cookie: __Host-bridgeops-refresh, Secure, HttpOnly, SameSite=Lax, Path=/, no Domain. The corresponding __Host-bridgeops-csrf cookie is readable by JavaScript, Secure, same-site and host-only. Deletion uses the same name/path/flags. Explicit loopback-only HTTP mode uses distinct bridgeops-dev-refresh and bridgeops-dev-csrf names without Secure; a non-loopback origin in that mode fails startup.

The signed double-submit CSRF token contains a random nonce, a thirty-minute expiry and HMAC-SHA-256 signature using separate 32-byte signing material. The signature binds the token to the hash of the presented refresh credential, or a pre-auth context when absent. A matching attacker-chosen cookie/header pair cannot forge it. Login and refresh issue replacement cookies bound to the new credential. GET /csrf can recover an expired CSRF token without granting authentication. Unsafe auth operations require exactly one X-CSRF-TOKEN header; request-parameter fallback and duplicate headers are rejected. Exact configured Origin checks are independent of Host, X-Forwarded-Host and X-Forwarded-For. Missing, null, duplicate, trailing-slash or different-scheme/port origins fail closed. No credentialed cross-origin deployment is configured.

## Shared bounded throttling

V3 creates authentication_throttles. PostgreSQL transaction/advisory locks serialize allocation/counting across replicas, without holding that lock during BCrypt work. Counters expire and are cleaned on acquisition; at most 10,000 rows can be allocated by the service. Full storage fails closed with 429. Stored keys are hashes of quota scopes, not raw email/IP strings; these hashes are not a claim of anonymity for low-entropy identifiers.

Defaults: all bootstrap/auth mutations share 300 requests/minute globally and 120/minute per socket peer. Login additionally permits 20 attempts/15 minutes per peer and 10/15 minutes per normalized email. Successful attempts count too; attackers cannot reset an account limit by changing IP or email case. Windows are fixed from first acquisition and rejection does not extend them. Forwarded headers are deliberately ignored; users behind the current nginx edge share its conservative peer limit. Production traffic sizing, fair trusted-edge attribution, distributed edge abuse mitigation and password-reset quotas remain future deployment work. The row cap is not a database retention policy for identity/security events.

## Configuration and local setup

Fresh checkout, PowerShell 7:

```powershell
./scripts/setup-local.ps1
./scripts/setup-auth.ps1
docker compose --env-file .env -f infra/compose.yaml up --build -d --wait --wait-timeout 180
```

For an existing .env, run only setup-auth.ps1 once. It uses exclusive file creation, refuses existing key material, writes secrets/auth/access-private.pem (PKCS8), access-public.pem (X509), and csrf.key (32 random binary bytes), and prints no values. It leaves database passwords and volumes untouched. The ignored directory is excluded from Docker build contexts and mounted read-only; the keys are not image layers. Restrict host filesystem access to operators. Local inherited permissions are not a managed production secret store. Production should use appropriately permissioned secret mounts for the non-root runtime and a separate migration job.

Compose enables auth with exact origin http://localhost:8088 (or BRIDGEOPS_PORT). For Vite, explicitly set AUTH_ORIGIN=http://localhost:5173 in the ignored .env and recreate the backend; only one origin is trusted at a time. Existing status-shell assets are unchanged and do not implement sign-in. No local account exists until Phase 1C provisions one.

Outside Compose, auth defaults disabled with the original default-denied chain. Explicit enablement requires all signing configuration; missing/invalid key files fail startup. Properties and environment names:

| Property | Environment name / purpose |
| --- | --- |
| bridgeops.auth.enabled | BRIDGEOPS_AUTH_ENABLED=true |
| bridgeops.auth.origin | BRIDGEOPS_AUTH_ORIGIN, exact HTTPS public origin |
| bridgeops.auth.local-http | BRIDGEOPS_AUTH_LOCAL_HTTP=false in production |
| bridgeops.auth.issuer | BRIDGEOPS_AUTH_ISSUER, stable configured issuer |
| bridgeops.auth.audience | BRIDGEOPS_AUTH_AUDIENCE, default bridgeops-browser |
| bridgeops.auth.key-id | BRIDGEOPS_AUTH_KEY_ID, active key ID |
| bridgeops.auth.private-key | BRIDGEOPS_AUTH_PRIVATE_KEY, mounted PKCS8 PEM path |
| bridgeops.auth.public-key | BRIDGEOPS_AUTH_PUBLIC_KEY, mounted X509 PEM path |
| bridgeops.auth.csrf-key | BRIDGEOPS_AUTH_CSRF_KEY, mounted 32-byte binary path |
| bridgeops.auth.previous-public-key | BRIDGEOPS_AUTH_PREVIOUS_PUBLIC_KEY, optional old public PEM path |
| bridgeops.auth.previous-key-id | BRIDGEOPS_AUTH_PREVIOUS_KEY_ID, paired old ID |
| bridgeops.auth.previous-accept-until | BRIDGEOPS_AUTH_PREVIOUS_ACCEPT_UNTIL, paired absolute ISO-8601 UTC deadline |

All three previous-key fields are supplied together or omitted. Compose's local key ID/issuer are development defaults; use an explicit production deployment configuration with HTTPS, Secure cookies and independently managed signing material. The existing reverse proxy enforces a 256 KiB request-body limit; direct production backend exposure is not configured.

## Verification

```powershell
./backend/mvnw.cmd -f backend/pom.xml -B -ntp "-Dtest=AccessTokensTest,BrowserSecurityTest" test
./backend/mvnw.cmd -f backend/pom.xml -B -ntp "-Dit.test=AuthenticationHttpIT" test-compile failsafe:integration-test failsafe:verify
./backend/mvnw.cmd -f backend/pom.xml -B -ntp verify
node scripts/verify-docs.mjs
docker compose --env-file .env -f infra/compose.yaml config --quiet
```

The initial focused 15-case HTTP run passed before three additional boundary/concurrency cases were added. The complete fresh suite, container checks, secret gate, actual commit/push and CI evidence are recorded in PROJECT_STATUS.md and the increment report after execution. Test accounts and keys exist only in disposable fixtures. This does not verify a browser sign-in journey, production load, cloud TLS deployment, tenant isolation, reset/invitation flows or production security as a whole.