# ADR-0014-offline-bootstrap: Offline initial account and tenant onboarding

Status: Accepted design, 2026-10-15. Implementation evidence is separate in PROJECT_STATUS.md.

## Context

Fresh installations have no identity, but no public privileged-registration endpoint is acceptable.

## Alternatives

Public privileged registration; implicit startup provisioning; silent dependency override. Rejected because they weaken explicit security/compatibility boundaries.

## Decision

An explicit disabled-by-default non-web command locks singleton installation state, consumes it while creating one verified initial account with organization.create grant, and refuses repeats. It accepts secret input via stdin/mount, never CLI argument. First authenticated organization creation atomically adds Owner membership. Subsequent users accept authorized recipient-bound invitations. Production uses operator-controlled offline provisioning, never normal startup.

## Consequences

No HTTP bootstrap or default account/password. Tests must cover default disabled mode, concurrent/repeated bootstrap, account/organization transaction rollback and invite privilege boundaries.

Phase 1C implementation clarification (2026-10-16): first-time invitation recipients enroll with the emailed single-use token plus matching email and initial password, then sign in explicitly. Existing recipients must authenticate as that email; invitations never reset an existing password or grant organization.create. This token-guarded setup is not general registration. SMTP uses external operator credentials; tests substitute an in-memory sink. Runtime cannot INSERT users directly; a tightly scoped migration-owned function validates/consumes invitation proof and writes the invited account/membership atomically. [Implementation and verification](../../development/PHASE_1C_ONBOARDING.md).
