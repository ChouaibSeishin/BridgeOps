# ADR-0013-released-stack: Released compatible stack

Status: Accepted design, 2026-10-15. Implementation evidence is separate in PROJECT_STATUS.md.

## Context

Original requirements named JUnit 5 while the current supported Boot BOM uses Jupiter 6. Frontend latest TypeScript exceeds the lint peer range.

## Alternatives

Public privileged registration; implicit startup provisioning; silent dependency override. Rejected because they weaken explicit security/compatibility boundaries.

## Decision

Pin Boot 4.1.1/Java 21/Maven 3.9.16 and managed Jupiter 6.0.3; explicitly amend JUnit major. Pin Node 24.22.0, React 19.3.1, TypeScript 5.9.3 and Vite 8.3.4. Use exact npm lock, managed backend dependencies and digest-pinned containers.

## Consequences

No silent downgrade or latest dependency drift. Exact-pair build/integration tests are still required.
