# Phase 1B.2 — HTTP authentication

## First increment: access signing and verification

AccessTokens uses Spring Security JOSE (version managed by the existing Boot BOM), Nimbus RS256, RSA keys of at least 2048 bits and explicit locally configured key IDs. Tokens contain identity only: sub, sid, iss, aud, iat, exp and jti. The audience is exact, not a source of permissions. The ten-minute validity is capped by the seven-day refresh-family deadline. Validation requires all claims, valid UUID identifiers, a valid signature, the expected algorithm/type, no future issuance and a strictly future expiry; there is no expiry grace interval.

One previous public key can be accepted until an explicit absolute deadline at most ten minutes after configuration. Unknown key IDs cannot trigger network lookup. Restarting with an unchanged deadline cannot extend overlap. Issued credentials redact their diagnostic string. Live database checks are still required when the HTTP boundary authenticates a request; JWT verification alone grants no access.

Changed files: backend/pom.xml, identity/application/AccessTokens.java, identity/AccessTokensTest.java and project documentation. The existing deny-by-default HTTP chain and internal refresh lifecycle are preserved by this increment. No authentication endpoint or signing secret is added to application configuration yet.

Verification commands (run from the repository root):

```powershell
./backend/mvnw.cmd -f backend/pom.xml -B -ntp "-Dtest=AccessTokensTest" test
./backend/mvnw.cmd -f backend/pom.xml -B -ntp verify
```

The five focused tests cover claim allowlists, signatures, malformed credentials, exact expiry, family expiry caps, wrong issuer/audience, missing claims, key IDs/types and bounded rotation. Full verification results are recorded in PROJECT_STATUS.md after execution. No previous session's test output is substituted for the current run.

## Next increment

Enable authentication only as a complete HTTP boundary: configured signing material, live account/family validation, typed DTOs, host-only refresh and signed session-bound CSRF cookies, exact configured Origin matching, PostgreSQL-backed throttling, safe errors and negative security integration tests. Keep every unrelated business route denied. There is no public registration or automatic account provisioning.