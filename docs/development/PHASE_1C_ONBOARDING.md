# Phase 1C — offline bootstrap and tenant onboarding

## Scope and sequence

1. V4 offline bootstrap: one verified initial account and organization.create account grant, no tenant.
2. Organization creation/listing: tenant-owned fixed role templates, composite membership-role constraints, live authorization and atomic Owner membership.
3. Member/invitation boundaries: tenant-scoped reads, delegated grants, serialized last-Owner changes and recipient-bound one-use invitations with externally configured SMTP plus an in-memory test delivery sink. No real recipients are contacted during verification.

Browser login/onboarding remains Phase 1D. The old UI sentence in the Phase 1 implementation sequence is superseded by the current request/backlog. Workflow/integration/AI work remains later.

## Offline command

V4 adds nullable users.email_verified_at, singleton installation_state, and user_grants. The marker contains both consumed time and initial account ID or neither. The operator transaction locks the singleton, refuses consumed/missing state or preexisting accounts, creates one verified ACTIVE account with BCrypt, adds only organization.create, consumes the marker and records BOOTSTRAP_COMPLETED. Any failure rolls back every write. It creates no organization/membership or platform grant. The runtime role cannot read/update installation state, insert users or assign account grants.

The jar dispatches only the exact bootstrap subcommand before starting Spring. The command starts no web context and runs no migrations. It requires BOOTSTRAP_ENABLED=true, BOOTSTRAP_EMAIL, BOOTSTRAP_DISPLAY_NAME, DATABASE_URL, DATABASE_USER and DATABASE_PASSWORD; database credentials use operator-controlled environment/secret injection, never CLI arguments. Apply migrations first. Normal application startup does not inspect these bootstrap settings or provision an account.

Start the normal Compose stack first, then an operator may explicitly run:

```powershell
docker compose --env-file .env -f infra/compose.yaml run --rm -e BOOTSTRAP_ENABLED=true -e BOOTSTRAP_EMAIL=your-email@example.com -e BOOTSTRAP_DISPLAY_NAME=YourName bootstrap
```

Replace the nonsensitive account metadata. The interactive Java console reads the password without echo when available. For automated operation use protected stdin (single UTF-8 line) with docker compose run --rm -T and the same flags; pass no password argument and do not put a literal password in shell history. PowerShell's normal string pipeline/transcription can expose secrets; use a controlled native stdin stream or protected input file. The command accepts no extra positional arguments, validates configuration before reading input, bounds input, clears password character arrays and emits only fixed safe messages. BCrypt requires a temporary immutable String internally; no Java implementation can promise that such memory is immediately erased.

The admin Compose profile is excluded from normal startup and explicitly disabled unless the operator overrides BOOTSTRAP_ENABLED. It uses the existing migration/operator role; production should inject a dedicated operator credential and run migrations separately. No account was provisioned into this workspace by the implementation tests. Do not reset the marker to bypass one-shot behavior. Exit codes: 0 success, 2 disabled/invalid input, 3 consumed/unavailable installation, 4 database/schema/permission failure. Failed commands never print passwords, email, connection strings or exception details.

## Verification commands

```powershell
./backend/mvnw.cmd -f backend/pom.xml -B -ntp "-Dtest=OfflineBootstrapTest" "-Dit.test=BootstrapIT" verify
./backend/mvnw.cmd -f backend/pom.xml -B -ntp verify
node scripts/verify-docs.mjs
docker compose --env-file .env -f infra/compose.yaml config --quiet
```

Bootstrap tests use disposable PostgreSQL and synthetic input: success/verified account/grant, repeated and concurrent execution, an audit-failure rollback, preexisting-account refusal, invalid password, disabled/missing configuration, argument/input redaction, runtime privileges and a command round trip. Foundation tests assert that ordinary startup leaves users empty and installation state unconsumed. Actual executed results, commits and hosted CI are recorded separately in PROJECT_STATUS.md and the task report. This first increment does not claim tenant or invitation behavior is implemented.

## Organization boundary (V5)

Implemented: POST /api/v1/organizations with {name,slug}, GET /api/v1/organizations?page=0&size=25, GET /api/v1/organizations/{id}, GET .../{id}/members and GET .../{id}/roles. All require a live bearer identity; creation also requires the current account-level organization.create grant, exact Origin and signed CSRF. Creation returns 201 with Location and {id,name,slug,revision}; list/member responses use {items,page,size,totalElements,totalPages}, maximum size 100. Role catalog returns tenant-local {id,name} pairs; it grants no authority. Member summaries expose IDs, status, revision and assigned roles, never identity entities/password hashes.

Organization creation locks the account, rechecks live identity and the current account grant, then creates the tenant, six fixed tenant-owned role templates, permission mappings, one Owner membership and a safe audit event in a single transaction. Duplicate slugs return a generic 409. No member/role/permission/user ID is accepted in the creation DTO; unknown fields are rejected. List queries qualify current user and ACTIVE membership/organization. Detail, member and role reads require a current membership and operation permission; foreign/missing tenant IDs return the same 404. Tenant permissions are read from current database mappings, not JWT claims or platform grants.

V5 uses organization-qualified unique parent keys and composite foreign keys on membership-role and role-permission joins. Runtime audit rows are read/insert only. AccountAuthority is the public identity application boundary; organizations never import identity repositories/entities. There are no membership mutation, role-definition mutation or invitation routes in this increment, so no incomplete last-Owner policy is exposed.

Focused tests cover atomic creation, current account grant removal, own-only listings, foreign organization/member/catalog concealment, cross-tenant role FK rejection, malformed/forged authority fields, CSRF/Origin/live-account denial, audit-failure rollback and duplicate-slug concurrency. Subsequent verification adds current tenant-permission/suspension and direct service identity checks. These tests use disposable fixtures, not local account provisioning.

Final organization verification: 63 tests passed in the full backend run; after the grant-lock adjustment all nine organization HTTP cases passed using bridgeops_app as the application datasource, separate operator credentials for migrations/fixtures, and the actual runtime DML grants. This exposed and corrected the inappropriate FOR SHARE grant-row lock without granting runtime UPDATE on user_grants. Compose upgraded the existing volume to V5 and started healthy without seeding tenants.
