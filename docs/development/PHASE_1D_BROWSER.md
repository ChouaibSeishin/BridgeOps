# Phase 1D — browser onboarding, sessions and recovery

## 1D.1 browser session boundary

The frontend now uses the real authentication endpoints. Sign-in and refresh obtain signed CSRF under the origin-wide bridgeops-session Web Lock and omit Authorization. Access tokens remain in application memory; refresh credentials remain in HttpOnly cookies. All protected requests and anonymous mutations share the lock, including CSRF acquisition, so another tab cannot rotate cookies halfway through a cookie-sensitive sequence. No credentials are broadcast or persisted. BroadcastChannel sends only session-changed to clear other tabs after sign-in/sign-out; tabs then require explicit restoration/sign-in. The lock also works when BroadcastChannel is unavailable.

Current secure-context browsers with Web Locks are required (HTTPS or loopback development). Unsupported browsers fail closed with an explicit message. No unsafe localStorage lock fallback is used. Reload initializes once through CSRF, refresh and live /me. Expired credentials refresh before requests; a 401 permits one serialized refresh/retry. Network failures and 403/409/429 are not automatically retried. Backend live checks remain authoritative.

Sign-out and logout-all clear in-memory state even if the network fails; the UI distinguishes a locally cleared session from confirmed server revocation. No individual remote-session list is implied. Sign-in clears the password form after each attempt. Sensitive fetches use same-origin credentials, no-store and reject redirects. No service worker or persistent query cache is installed.

Focused frontend verification covers initialization, expired access, concurrent unauthorized calls, separate tab clients sharing a lock, failed refresh/network handling, both logout modes and safe form errors. Hosted CI must be inspected after publication; a build alone does not verify a real browser journey. Organization onboarding, recovery and end-to-end verification follow in the remaining increments.

## Operator prerequisites

Normal startup still provisions no account. Follow the [offline bootstrap guide](PHASE_1C_ONBOARDING.md), then open the same-origin frontend and sign in. Authentication must be enabled with signing material and the exact browser Origin; see [HTTP configuration](PHASE_1B_HTTP_AUTH.md). No persistent local account or real-recipient email is created by implementation verification. External SMTP remains disabled until configured.

## 1D.2 organization browser experience

The signed-in landing page lists real organizations and the API's pagination totals. The create form is shown from UserSummary.canCreateOrganizations, derived from current account grants on /me/login/refresh, never inferred from a tenant role. The backend still rechecks that grant at creation. Organization details expose current members, tenant-local roles, revision-checked changes/removal, invitation issuance/listing/revocation and safe errors. Client hints do not override server authorization. React Query state is memory-only, scoped by user and cleared when signed out.

New bounded reads: GET O/access returns {membershipId,permissions} for the caller's ACTIVE membership only; GET O/invitations?page=0&size=25 requires membership.invite and returns safe paginated {id,email,expiresAt,status,roleIds}, never tokens/hashes. Status is PENDING, EXPIRED, REVOKED or ACCEPTED. This listing is necessary so an invitation remains manageable after navigation/reload. Foreign/missing tenants remain uniformly 404. GET O/roles adds assignable, calculated from current permission subset and privileged-role rules. No migration or runtime privilege expansion is needed.

Existing recipients sign in, open Accept an invitation, and paste the emailed token. New recipients use Join with an invitation from sign-in, fill the invitation-only enrollment form and explicitly sign in afterward; enrollment creates no session. No proof is read from a URL. Unusable invitations retain a generic rejection because the anonymous API deliberately conceals exact failure reasons. SMTP-disabled/failure responses are shown as unavailable, never successful delivery.

Membership removal asks for confirmation and sends the current quoted revision in If-Match. Editing sends expectedRevision, status and tenant-local role IDs. A conflict requires reloading; last-active-Owner and delegation constraints remain enforced by the existing backend. There is no role-definition editor or fabricated workflow data.
