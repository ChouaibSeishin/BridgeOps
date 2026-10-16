# Security threat model and authorization

Status: mixed implemented and proposed controls; current tested boundaries are recorded below and in PROJECT_STATUS.md. Assets: accounts/tokens, tenant business data, integration secrets, published definitions, approvals, external write authority and audit evidence. Adversaries include unauthenticated callers, compromised accounts, malicious members, forged senders, hostile external content and compromised dependencies.

## Trust boundaries

Browser -> TLS edge -> adapters -> application authorization -> tenant repositories. Workers cross from persisted messages into revalidated authority. Connector calls leave the trust boundary. LLM responses and ticket text are untrusted. MCP clients are separate authenticated clients, never trusted because they speak the protocol. Managed-secret access and database maintenance roles have separate credentials. Platform administration grants no automatic tenant access.

## Threats, controls and required verification

| Threat | Planned control | Evidence required |
| --- | --- | --- |
| Account guessing/enumeration | BCrypt cost 12 subject to timing benchmark; generic errors; bounded login/reset throttling | Bad-password, enumeration and 429 tests |
| Token theft/replay | 10-minute signed access JWT; opaque 256-bit refresh; hashed storage; single-use rotation; family revocation | Expiry, issuer/audience/signature, replay and concurrent-rotation tests |
| CSRF | Same-origin deployment, strict Origin validation, CSRF header token and host-only cookies on cookie-sensitive endpoints | Missing/wrong CSRF, hostile origin, login/refresh/logout tests |
| Cross-tenant IDOR | Verified membership context, tenant-qualified queries, composite FKs and default deny | Every resource family queried/mutated with another tenant's IDs |
| Role escalation | Fixed grants; assign only permitted roles; owner-only privileged grants; serialize last-owner checks | Foreign-tenant role, last-owner race and self-escalation tests |
| Approval bypass or replay | Central policy gate, exact-intent binding, distinct human approver, single final decision | Removed node, changed arguments, expired/revoked grants and double-decision races |
| SSRF and DNS rebinding | Fixed destination/operation catalog; HTTPS allowlist; validate all IPv4/IPv6 answers, pin validated destination with hostname/TLS verification, disable redirects; egress restriction | Private/link-local/metadata, mixed DNS, IPv6, redirect, rebinding and credential-forwarding tests |
| Secret disclosure | Secret references, server-side adapters, DTO allowlists, redaction and payload limits | Canary secrets absent in APIs, errors, audit and captured logs |
| Webhook forgery/replay | HMAC-SHA-256 over timestamp and exact raw bytes; constant-time compare; five-minute skew; delivery key | Altered bytes, unknown/rotated key, timestamp and duplicate/concurrent delivery tests |
| Prompt injection/excessive AI authority | Authorized tool list, strict argument schemas, per-call reauthorization, approvals, quotas, no shell/SQL | Hostile ticket content cannot grant tools or execute unauthorized writes |
| MCP impersonation/confused deputy | Audience-bound authentication; token tenant/grant context; no token passthrough; tool schemas; Origin/Host validation | Unauthenticated/wrong-audience/cross-tenant calls and side-effect policy tests |
| Duplicate/lost business changes | Durable work, fenced leases, operation receipts, provider preconditions, reconciliation | Crash-before/after-effect and ambiguous timeout tests |
| XSS/data exfiltration | Escaped rendering, sanitize optional markdown, CSP, no untrusted HTML; minimize provider data | Injection rendering and malicious-link tests |
| Resource exhaustion | Per-tenant quotas, bounded payloads/graphs/workers, timeouts and body limits | Saturation, oversized body, retry storm and provider-limit tests |
| Audit tampering | Runtime insert-only privilege, restricted reader and separate retention role | Runtime UPDATE/DELETE denied; no raw tokens in records |
| Supply-chain/configuration | Lockfiles/BOM, maintained versions, scanning, least-privilege CI and pinned actions/images | Review dependency reports and container/config checks |

SSRF checks must constrain the actual connected address, not just a preflight DNS lookup. No generic connector is enabled until this gate passes. Internal demo capabilities use in-process adapters; any local network test exception is test-profile-only and unreachable in production configuration.

## Session and browser policy

Proposed JWT claims: sub, sid (family), iss, aud, iat, exp, jti. Explicit algorithm allowlist and signing-key IDs; rotate keys with bounded overlap. JWT carries identity, not authoritative role assignments. Every authenticated request checks family/account status in PostgreSQL, giving immediate revocation. Permission resolution checks current organization membership/grants. Database outage fails closed for protected operations.

Refresh lifetime: seven-day absolute family limit; each rotated token cannot outlive the family. Production refresh cookie: __Host-bridgeops-refresh, Secure, HttpOnly, SameSite=Lax, Path=/, no Domain. Access token stays in memory. A separate host-only CSRF cookie/token is readable for the header and bound to the session/pre-auth context; validate header plus exact Origin on login/refresh/logout/reset as applicable. Fetch bootstrap CSRF token via a dedicated same-origin endpoint. CORS is disabled by default with same-origin routing; any split-origin deployment needs an exact configured allowlist, never credentialed wildcard CORS. Local HTTP uses explicitly development-only cookie settings and a distinct cookie name.

No refresh tokens in localStorage. XSS can still act within a live browser session; in-memory storage does not make XSS harmless. Password reset tokens expire after 15 minutes and are one-use; reset/change revoke sessions. Invitation expiry is 48 hours. These defaults will be configurable and tested with an injected clock when implemented. Mail/outbox tokens are shown only in an authenticated development delivery interface or test sink, never normal application logs.

## Permission-to-role baseline

Permissions below are exact proposed codes. Read bundle R = organization.read, membership.read, integration.read, workflow.read, execution.read, approval.read, capability.read. Mutation authority is checked separately; execution.run alone cannot authorize arbitrary capability writes.

| Role | Permission set |
| --- | --- |
| Owner | R + organization.manage, ownership.transfer, membership.invite, membership.manage, membership.grant_privileged, policy.manage, integration.manage, workflow.edit, workflow.publish, execution.run, execution.retry, execution.cancel, capability.write, approval.decide, audit.read, ai.use |
| Organization administrator | R + membership.invite, membership.manage (nonprivileged roles only), integration.manage, workflow.edit, workflow.publish, execution.run, execution.retry, execution.cancel, capability.write, audit.read, ai.use |
| Builder | R + integration.manage, workflow.edit, workflow.publish, capability.write, ai.use |
| Operator | R + execution.run, execution.retry, execution.cancel, capability.write, ai.use |
| Approver | R + approval.decide, audit.read |
| Viewer | R |
| Platform administrator | platform.configuration.manage, platform.health.read; no tenant grants |

Builder needs an additional operator grant to run/test; UI explains that requirement. Owner permission breadth never overrides the self-approval prohibition. An owner who is the only member must invite a different approver. Organization administrator cannot assign Owner/Admin or any grant outside its allowed delegation set. Role combinations form a union of grants but policy restrictions remain mandatory.

Automation principals receive explicit per-workflow/connector operation grants, bounded by organization policy. They cannot decide approvals, invite members or expand their own grants. Webhook execution records both automation principal and source. Sensitive actions initiated by automation require a human approver independent of the configuring/requesting actor as defined by policy. Suspension or grant removal blocks future dispatch; already-completed external effects cannot be undone automatically.

Approval cardinality: one requester plus one different eligible decision-maker. Exactly one final approve OR reject decision per request; this is not a two-approver quorum. Backend checks the distinct actor and all policy conditions regardless of frontend controls.

Use a SecretStore interface. Local secret references resolve explicitly supplied environment/mounted-file values; no UI promise of dynamic secret persistence until implemented. If persistent credential entry arrives, use a managed store or AEAD envelope encryption with keys outside PostgreSQL, key versioning and tested rotation. Never put raw credentials in integration config JSON. Outbound destinations bind credential usage to a host and operation; credentials cannot be forwarded through redirects.

Audit includes actor type/ID, organization when tenant-scoped, action, resource, safe outcome, correlation ID and sanitized metadata. Correlation IDs are validated/generated, not blindly trusted log text. Default retention is documented in the database model. Provider conversation retention is separate from BridgeOps storage and must be disclosed for a selected adapter.

Residual risks: single-database application tenancy depends on complete query discipline; no RLS yet; local secret configuration is not a managed vault; no SSO/MFA yet; external services may lack idempotency/conditional updates; no cryptographic tamper evidence; no production HA, recovery-time commitment or completed penetration test. Remote MCP authorization design must be verified against the chosen protocol/SDK at Phase 6; do not publish a bearer-token demo as an interoperable production authorization server.

## Reference guidance

Controls are design decisions informed by [OWASP multi-tenant security](https://cheatsheetseries.owasp.org/cheatsheets/Multi_Tenant_Security_Cheat_Sheet.html), [SSRF prevention](https://cheatsheetseries.owasp.org/cheatsheets/Server_Side_Request_Forgery_Prevention_Cheat_Sheet.html) and [CSRF prevention](https://cheatsheetseries.owasp.org/cheatsheets/Cross-Site_Request_Forgery_Prevention_Cheat_Sheet.html). These references do not establish that BridgeOps implements their guidance.

## Phase 1B.1 implementation boundary

BCrypt and the hashed opaque-refresh lifecycle are now implemented as internal services with transaction/concurrency tests. That was the ffa151d checkpoint. Phase 1B.2 now implements the login/refresh/logout/me HTTP adapter, signed JWTs, refresh/CSRF cookies, exact Origin checks and shared bounded throttling; browser sign-in, password setup/reset and tenancy remain future work. Refresh replay commits family revocation and a safe event even when the result is rejected. Email identifiers are ASCII with trim/ROOT-lower normalization matched by PostgreSQL C-collation checks. Runtime event rows are insert/read only. These checks do not establish tenant isolation or complete authentication security. Details and limitations are in [the session-core report](../development/PHASE_1B_SESSION_CORE.md).

Local migration credential limit: the current Compose backend also receives Flyway credentials for startup migrations. Database-role privilege checks do not isolate those credentials from a compromised backend process. A production deployment must run migrations with a separate operator/job and omit migration credentials from the long-lived runtime. This is a documented development limitation, not production containment.

## HTTP implementation boundary

The current boundary uses RS256, explicit local key IDs, one previous public key with an absolute overlap deadline, identity-only claims, and live PostgreSQL family/account checks. Signed double-submit CSRF cookies are bound to the presented refresh credential (or a pre-auth context), expire after 30 minutes and change on login/refresh. Unsafe auth requests require one exact configured Origin plus one X-CSRF-TOKEN header; parameter fallback is disabled. Production cookies use the __Host- prefix and Secure; only explicit loopback development configuration permits HTTP with distinct names. The edge's peers currently share a conservative network quota because forwarded headers are ignored. Per-account/global limits, PostgreSQL allocation locks, expiry cleanup and a 10,000-row cap bound throttle state. These controls do not establish production capacity, tenant isolation or a complete browser authentication UX. [Configuration and verification](../development/PHASE_1B_HTTP_AUTH.md).

## Phase 1C tenant/onboarding boundary

Offline bootstrap is explicit, non-web and disabled by default, with a row-locked consumed installation marker and account-only organization.create grant. Tenant creation/membership/role/invitation queries qualify the actor/organization and enforce current permissions, delegation ceilings, revision checks and serialized last-active-Owner protection. Existing accounts require authenticated matching-email invitation acceptance; first-time setup requires emailed one-use proof and never changes an existing password or grants account-level authority. Generic runtime users INSERT remains denied; the migration-owned enrollment capability independently validates proof, current issuer/roles and a DB-clock deadline, with qualified relations/trusted search path and restricted EXECUTE. SMTP authenticates over required TLS, verifies hostnames, bounds timeouts and sanitizes provider failures; invitation nonces are excluded from URLs, response DTOs and audit records. [Details and residual deployment limits](../development/PHASE_1C_ONBOARDING.md). No RLS, compromised-runtime containment, durable email delivery, external SMTP connectivity, browser onboarding or production-security certification is claimed.
