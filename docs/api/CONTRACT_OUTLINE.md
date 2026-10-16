# API contract outline

Status: planned routes and DTO shapes. **Info/health, Phase 1B.2 auth and the Phase 1C organization/member/invitation routes listed in the onboarding guide are implemented. Remaining target routes are planned.** Runtime OpenAPI describes implemented routes only. `/api/v1` is the business base; actuator and MCP have separate transport paths.

## Shared conventions

Authenticated routes require bearer access token and live session/account. Tenant-scoped routes additionally require active membership, permission and tenant-qualified resource lookup; account-level organization creation checks its separate grant. Organization path IDs select a tenant; they do not prove membership. No membership or foreign-tenant resource returns a uniform 404; valid tenant members lacking the operation permission receive 403. Invalid authentication receives 401. Resource permissions are rechecked in application services.

Safe error DTO: `{code,message,details?,correlationId,timestamp}`. Details contain only safe field/schema validation information. Missing/malformed fields 400; invalid workflow semantics 422; stale revision/idempotency mismatch/state conflict 409; throttling 429 with Retry-After; transient dependency outage 503. Creation 201 with Location; accepted work 202 with execution reference; successful void action 204. Async acceptance never implies completion.

Collections: `?page=0&size=25`, maximum size 100, explicit sort allowlist and stable ID tiebreaker. Response `{items,page,size,totalElements,totalPages}`. Execution timeline instead uses `afterSequence` cursor. Times are UTC ISO 8601. DTOs never expose entities, password hashes, secret references usable as credentials, tokens or raw provider errors.

Draft/membership mutations use expectedRevision or If-Match; mismatch returns 409. Requests reject unknown security-sensitive fields. Start, test-run, notification/action-plan submission and webhook delivery use scoped idempotency keys with canonical payload hashes. Key mismatch returns 409; identical replay returns original resource. A 202 status is used only while that resource remains active; terminal replay returns its representation with 200. Published/archived definitions cannot be silently mutated or deleted.

## Phase 1A contract to implement first

| Route | Authorization | Response and checks |
| --- | --- | --- |
| GET /api/v1/system/info | Public, non-sensitive allowlist | 200 `{name,apiVersion}`; no host/config/build secrets |
| GET /actuator/health/liveness | Probe access, minimal output | 200 if application alive; database outage alone must not fail it |
| GET /actuator/health/readiness | Probe access, minimal output | 200 when database ready, 503 otherwise; no component details |

Phase 1A has no business APIs, membership or authentication; Phase 1B/C implements these. All other API paths are denied until implemented; only health/info are intentionally public. GET info is the real frontend integration, rather than fake organization/dashboard data.

## Target route families

Let `O=/api/v1/organizations/{organizationId}`. Codes match the security matrix.

| Phase | Planned operations | DTO outline and permission |
| --- | --- | --- |
| 1 | POST /auth/login, /refresh, /logout, /logout-all | Login `{email,password}` -> access token/expiry/UserSummary plus cookie. Refresh rotates cookie. CSRF/Origin on cookie-sensitive routes. Logout-all requires live user. Errors generic |
| 1 | GET /auth/csrf, /auth/me | CSRF bootstrap -> token; me -> user/session summary. Me requires authentication |
| 1 | POST /auth/password/change, /forgot, /reset | `{currentPassword,newPassword}` or `{email}` or `{token,newPassword}`. Forgot always 202; password actions invalidate families |
| 1 | POST/GET /organizations | Create `{name,slug}` -> organization/owner membership; list only own memberships. Authenticated active user with account-level organization.create grant for POST; GET lists own memberships |
| 1 | GET/PATCH O; POST O/ownership-transfer | OrganizationSummary; patch expectedRevision. organization.read/manage; transfer ownership.transfer with atomic last-owner protection |
| 1 | GET O/members; PATCH/DELETE O/members/{id} | MemberSummary/role IDs/status; membership.read/manage and delegation ceiling |
| 1 | POST O/invitations; POST /invitations/accept | `{email,roleIds}` -> safe invite summary; accept `{token}` with recipient identity. membership.invite on issue, one-use token on accept |
| 1 | POST /auth/verification/confirm | One-use verification token; invitation-verified enrollment can avoid redundant verification |
| 5 | GET /connectors; GET/POST O/integrations | ConnectorSummary and `{connectorKey,name,publicConfiguration,secretInput?}`. integration.read/manage. Secret input is write-only |
| 5 | GET/PATCH/DELETE O/integrations/{id} | Safe connection DTO; archive if referenced; never return decrypted credentials |
| 5 | POST O/integrations/{id}/test; GET .../health | Bounded connection test result and paginated checks. manage for test, read for history |
| 5 | GET O/support/tickets; GET .../{id}; GET O/teams | TicketSummary/Detail and TeamSummary; status/priority filters; capability.read |
| 3–5 | POST O/action-requests | `{capability,arguments,idempotencyKey}` -> 202 execution/approval reference. capability.write plus specific action policy; no direct approval bypass |
| 5 | GET O/notifications/{id} | Safe delivery status and attempt metadata; capability.read |
| 2 | GET/POST O/workflows; GET/PATCH/DELETE .../{id} | WorkflowSummary/Draft `{name,description,definition,expectedRevision}`. workflow.read/edit; DELETE archives |
| 2 | POST .../{id}/validate, /publish, /duplicate | Validate -> `{valid,errors:[{nodeId,path,code,message}]}`. Publish `{expectedRevision}` -> 201 VersionSummary; workflow.publish. Duplicate -> new draft; workflow.edit |
| 2 | GET .../{id}/versions; GET .../{id}/versions/{versionId} | Immutable summaries and exact permitted snapshot; workflow.read |
| 3 | POST .../{id}/executions; POST .../{id}/test-runs | `{versionId?,input,effectsAcknowledged?}` plus Idempotency-Key; 202 ExecutionSummary. execution.run and every reachable write capability checked; test-runs pin draft revision and require effectsAcknowledged=true; missing/false is 422. UI requires explicit real-effects confirmation |
| 3 | GET O/executions; GET .../{id}, /steps, /events | ExecutionSummary, sanitized StepAttempt/Timeline DTOs; execution.read |
| 3 | POST O/executions/{id}/cancel, /retry | Expected revision and reason. cancel -> 202 cooperative request; retry -> 202 new linked execution. execution.cancel/retry; permanent failures ineligible |
| 4 | GET O/approvals; GET .../{id} | Exact operation preview, requester, policy, expiry and decision status; approval.read |
| 4 | POST O/approvals/{id}/approve, /reject | `{expectedRevision,reason?}` -> decision/request. approval.decide plus eligibility. Same decision replay returns original; opposite/stale conflicting decision 409 |
| 5 | POST O/webhook-endpoints; POST .../{id}/rotate; DELETE .../{id} | Pinned workflow version/automation grant; manage integration and workflow policy. Secret returned once on create/rotate only |
| 5 | POST /webhooks/{publicWebhookId} | Raw bounded payload; HMAC timestamp/signature/key ID/delivery ID headers. Valid -> 202 execution ID. Wrong signature 401, stale timestamp 401, duplicate same digest existing reference, conflict digest 409, too large 413 |
| 6 | POST/GET O/ai/conversations; GET .../{id}; POST .../{id}/messages | `{message}` -> providerMode, response and validated tool trace or async execution reference; ai.use plus tool permissions; user-scoped conversation ownership |
| 6 | POST O/ai/action-plans/{planId}/submit | Persisted canonical plan -> normal execution/approval. ai.use, execution.run and action permissions; no second approve endpoint |
| 4/7 | GET O/audit-events; GET O/analytics/overview, /executions, /failures | Safe AuditEvent/aggregate DTOs, period/workflow/status filters; audit.read/execution.read respectively |

`/auth` routes in the table are relative to `/api/v1`. Support mutations (assignment/note), notification creation and MCP/AI writes dispatch through action requests and central policy services. Until Phase 3–4 implements safe execution/approval, gated write operations remain unavailable. Phase 5 reads/connection tests follow the synthetic capability introduced in Phase 3; Phase 2 test-run controls arrive with Phase 3, with their dependency explained instead of returning fake results.

## MCP and provider contract boundary

MCP tools have schema, permission and side-effect classifications. Read tools call authorized query services. `request_ticket_assignment` and `create_notification_request` submit canonical operations; they cannot approve their own requests. REST remains the browser/business API; MCP serves compatible tool clients and does not replace it. Selected SDK/transport/authentication details are a Phase 6 gate.

Mock AI responses identify `providerMode=MOCK`. Provider output is schema-validated; unknown tools/arguments fail before dispatch. Simple read questions use bounded synchronous responses; long work returns an execution ID. API keys remain server-side. Conversation content is transient unless retention is explicitly enabled.

## Initial account lifecycle

There is no bootstrap or public registration HTTP route. The offline command provisions one verified account, not a tenant, and consumes a singleton installation marker. POST /organizations checks the account-level organization.create grant, creates the tenant and its Owner membership in one transaction, then returns 201. Invitation acceptance requires the intended recipient and grants only checked tenant roles. Normal startup cannot provision accounts. See ADR 0014.

## Implemented Phase 1B.2 transport

With authentication explicitly enabled and signing material configured, GET /api/v1/auth/csrf and /me, and POST /api/v1/auth/login, /refresh, /logout and /logout-all are implemented. The rest of the target route table remains planned. See [the HTTP guide](../development/PHASE_1B_HTTP_AUTH.md) for exact DTOs, required headers, cookie settings, errors and configuration. Never serialize internal Granted objects. Unrelated business routes remain denied even with a valid bearer credential.
Phase 1C.1 adds only the offline bootstrap subcommand and V4 state/grant migration. It enables no HTTP route. Organization and invitation routes remain denied until their subsequent increment is verified. [Operator guide](../development/PHASE_1C_ONBOARDING.md).

Phase 1C.2 implements authenticated organization create/list/detail, member listing and read-only tenant role catalog. See [exact HTTP shapes and authorization](../development/PHASE_1C_ONBOARDING.md). Membership mutations/invitations and all other business routes remain denied until their own verified increment.

Phase 1C.3 enables PATCH/DELETE O/members/{membershipId} with live tenant permission, CSRF/Origin, expectedRevision or quoted If-Match, delegation ceilings and last-Owner protection. See [exact request/state rules](../development/PHASE_1C_ONBOARDING.md). Role-definition mutation and invitation routes remain denied in this checkpoint.

Phase 1C.4 implements POST O/invitations, DELETE O/invitations/{id}, authenticated POST /api/v1/invitations/accept, and invitation-only POST /api/v1/invitations/enroll. The enrollment extension is required for first-time invitees; a raw recipient-bound emailed token is mandatory, roles/grants are never body authority, and existing accounts require authenticated accept. It is not general registration. [Exact DTOs, statuses, SMTP and proof rules](../development/PHASE_1C_ONBOARDING.md). HTTP bootstrap, /auth/register and all unrelated business routes remain denied.

Phase 1D.1 integrates the existing authentication contract into the browser without adding backend routes. [Session client behavior](../development/PHASE_1D_BROWSER.md).

Phase 1D.2 adds UserSummary.canCreateOrganizations, GET O/access and bounded GET O/invitations; O/roles adds a live assignable hint. Existing mutating contracts are unchanged and remain authoritative. [Browser/API details](../development/PHASE_1D_BROWSER.md).

Phase 1D.3 implements POST /auth/password/change, /auth/forgot and /auth/reset with the exact shapes, generic acknowledgement and invalidation semantics in [the browser/recovery guide](../development/PHASE_1D_BROWSER.md). Forgot returns 202 without a delivery claim; change/reset success returns 204 and clears cookies. V8 reset state and shared outbound transport retain invitation SMTP configuration compatibility.
