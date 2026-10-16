Current increment: Phase 1C.3 membership role/status mutations and soft removal. Eighteen focused restricted-runtime tests passed, followed by a successful full verify with 72 tests (17 unit/MVC and 55 PostgreSQL), zero failures/errors/skips. V6 Compose upgrade/start passed; users and organizations both remain empty. Runtime privilege checks confirmed status/revision updates and parent locking while user ownership, audit and account-grant writes remain denied. Revision checks, current delegation ceilings and serialized last-active-Owner protection are required on every exposed mutation. Invitations remain the final pending increment.

# Project status

Updated 2026-10-16 UTC. Current task: Phase 1C backend onboarding. Browser work remains Phase 1D.

## Published checkpoints

Initial local/fetched origin/master matched ccf94bb with a clean tree. Foundation/session/JWT/HTTP commits b1517ec, ffa151d, f00a1e0 and ccf94bb are preserved. Their 2026-10-15 evidence remains historical; fresh checks below supersede it where rerun.

267645e implements one-shot offline initial-account provisioning. Push and remote hash were verified; [hosted CI passed](https://github.com/ChouaibSeishin/BridgeOps/actions/runs/38891771047). Full backend verify passed 53 tests before the final command-logging adjustment; final focused verification passed 3 CLI + 6 PostgreSQL bootstrap tests. CI reran the full backend/frontend/container checks. No persistent initial account was created.

## Published organization boundary

V5 implements tenant-owned fixed roles, permissions, membership composite keys and safe audit events. POST /api/v1/organizations creates the organization and Owner membership atomically after live identity/account-grant checks. GET collection/detail/member-list/role-catalog queries are user/tenant-qualified and require current permissions. All unimplemented business routes remain denied. Full backend verify passed 63 tests (17 unit/MVC and 46 PostgreSQL, zero failures/errors/skips). Final focused verification after removing a grant-row lock passed all 9 organization HTTP tests with the restricted runtime database role; fixtures/Flyway use a separate operator connection. Final Compose up --build -d --wait passed with all services healthy. Published as 43338a8; remote ref matched and [CI passed](https://github.com/ChouaibSeishin/BridgeOps/actions/runs/38893437874).

See [the Phase 1C implementation and commands](development/PHASE_1C_ONBOARDING.md), [HTTP session design](development/PHASE_1B_HTTP_AUTH.md), [API](api/CONTRACT_OUTLINE.md), [schema](architecture/DATABASE.md) and [security model](security/THREAT_MODEL.md).

## Verified operational boundaries

The final bootstrap image build/start passed. Default-disabled admin command exited 2 with a fixed safe message. Existing-volume upgrade installed V4; users count remained 0, marker unconsumed, runtime marker UPDATE/account-grant INSERT both denied. No local secrets were printed, tracked or rotated and no volume was deleted. CLI tests use disposable databases and synthetic secret input. Normal startup never provisions an account. The app remains available at localhost:8088 as the existing status shell.

Java 21, Boot 4.1.1, Maven Wrapper 3.9.16, PostgreSQL 18.6 and Node 24.22.0 remain unchanged. No dependency was added for bootstrap or the organization boundary. Hosted CI reruns frontend checks; frontend source and browser visuals are unchanged.

## Remaining Phase 1C work

Membership mutation requires delegated-role ceilings and serialized last-Owner checks before exposure. Invitations are included in the accepted backlog; the user selected externally configured SMTP plus a test delivery sink. Implement recipient binding, hashed one-use tokens, expiry/replay checks, privilege revalidation and token-guarded first-account setup for invitees without public registration. No real-recipient email will be sent during verification. These are pending, so Phase 1C is not complete.

No browser sign-in/client refresh coordination, account recovery, workflows, integrations or AI/MCP in this task. No production security/load/TLS/HA, WCAG, backend vulnerability-scan, backup/restore or managed-vault claim. Compose still shares migration credentials with startup runtime; production needs separate migration/operator execution and protected secrets. No license/private reporting channel is configured. Mermaid rendering is unverified; secret scanners cannot prove every possible secret absent.
