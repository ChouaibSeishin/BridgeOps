# ADR-0002-browser-sessions: Browser sessions and revocation

Status: Proposed. Date: 2026-10-15.

## Context

Browser refresh credentials must survive reload without being available to JavaScript.

## Alternatives

Long-lived JWTs in localStorage; server-only session cookies.

## Decision

Short-lived in-memory access JWT and rotating opaque HttpOnly refresh cookie, CSRF/Origin checks, hashed single-use token storage, family reuse detection and live family checks per request.

## Consequences

Immediate revocation costs a database lookup; strict refresh races may require login. Signing-key rotation and cookie tests are required.
