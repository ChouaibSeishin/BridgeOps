# Phase 1D — browser onboarding, sessions and recovery

## 1D.1 browser session boundary

The frontend now uses the real authentication endpoints. Sign-in and refresh obtain signed CSRF under the origin-wide bridgeops-session Web Lock and omit Authorization. Access tokens remain in application memory; refresh credentials remain in HttpOnly cookies. All protected requests and anonymous mutations share the lock, including CSRF acquisition, so another tab cannot rotate cookies halfway through a cookie-sensitive sequence. No credentials are broadcast or persisted. BroadcastChannel sends only session-changed to clear other tabs after sign-in/sign-out; tabs then require explicit restoration/sign-in. The lock also works when BroadcastChannel is unavailable.

Current secure-context browsers with Web Locks are required (HTTPS or loopback development). Unsupported browsers fail closed with an explicit message. No unsafe localStorage lock fallback is used. Reload initializes once through CSRF, refresh and live /me. Expired credentials refresh before requests; a 401 permits one serialized refresh/retry. Network failures and 403/409/429 are not automatically retried. Backend live checks remain authoritative.

Sign-out and logout-all clear in-memory state even if the network fails; the UI distinguishes a locally cleared session from confirmed server revocation. No individual remote-session list is implied. Sign-in clears the password form after each attempt. Sensitive fetches use same-origin credentials, no-store and reject redirects. No service worker or persistent query cache is installed.

Focused frontend verification covers initialization, expired access, concurrent unauthorized calls, separate tab clients sharing a lock, failed refresh/network handling, both logout modes and safe form errors. Hosted CI must be inspected after publication; a build alone does not verify a real browser journey. Organization onboarding, recovery and end-to-end verification follow in the remaining increments.

## Operator prerequisites

Normal startup still provisions no account. Follow the [offline bootstrap guide](PHASE_1C_ONBOARDING.md), then open the same-origin frontend and sign in. Authentication must be enabled with signing material and the exact browser Origin; see [HTTP configuration](PHASE_1B_HTTP_AUTH.md). No persistent local account or real-recipient email is created by implementation verification. External SMTP remains disabled until configured.
