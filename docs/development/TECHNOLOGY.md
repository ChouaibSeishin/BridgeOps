# Locked technology stack

Decision date: 2026-10-15 UTC. Stable released artifact availability was checked directly against Maven Central, npm and official runtime metadata. These choices replace the Phase 0 candidates. Resolution/build evidence is recorded in DEPENDENCY_LOCK.md and PROJECT_STATUS.md; a release listing alone is not a passing build.

| Component | Exact selected version / policy | Rationale |
| --- | --- | --- |
| Java | Java 21 LTS, maintained Temurin container pinned by digest | Compile release 21. Host 21.0.4 is observed only; container runtime patch recorded after pull. No global upgrade assumed. |
| Spring Boot | 4.1.1 parent/BOM | Official system requirements include Java 21 and Maven 3.6.3+. No snapshots or unreviewed dependency overrides. |
| Maven | 3.9.16, Wrapper plugin 3.3.4 | Latest stable 3.9 artifact verified; do not adopt Maven 4 release candidate. Distribution SHA-256 pinned. |
| JUnit Jupiter | 6.0.3, Boot-managed | Explicit amendment of original JUnit 5 requirement. Preserve Jupiter test style without fighting the supported BOM. |
| Mockito | 5.23.0, Boot-managed | Same dependency-management authority as JUnit. |
| Testcontainers | 2.0.5, Boot-managed | PostgreSQL and JUnit Jupiter modules; required integration tests fail when Docker is unavailable. |
| PostgreSQL server | 18.6-alpine, immutable image digest | Supported database line; same major in local runtime and database tests. |
| JDBC | 42.7.13, Boot-managed | PostgreSQL driver from selected BOM. |
| Flyway | 12.4.0, Boot-managed | Core plus PostgreSQL database module; schema owned by migrations, JPA validate only. |
| Spring Security | 7.1.1, Boot-managed | Deny business APIs until implemented; authentication follows existing session ADR in Phase 1B. |
| OpenAPI | springdoc WebMVC UI 3.1.1 | Official Boot 4 family support; exact combination passed the PostgreSQL/OpenAPI integration smoke test. |
| Node | 24.22.0 LTS | Official checksum-verified project-local Windows runtime; production build uses matching pinned container. Replaces incompatible global 20.17.0 for project commands. |
| npm | Bundled with Node 24.22.0 | Exact reported version recorded after installation; package-lock committed, CI uses npm ci. |
| React / DOM | 19.3.1 / 19.3.1 | Matching released runtime packages. |
| TypeScript | 5.9.3 | Intentionally retain compatible stable 5.9 rather than latest 7.0.2: typescript-eslint 8.71.2 declares TypeScript <6.1.0. |
| Vite / React plugin | 8.3.4 / 6.1.2 | Plugin declares Vite 8 peer support; Node 24.22 satisfies both engines. |
| Vitest / jsdom | 5.0.4 / 30.1.3 | Node 24.22 satisfies strict test engines; Vite 8 supported. |
| RTL / jest-dom / DOM | 16.3.3 / 7.0.1 / 10.4.2 | Accessible UI behavior tests. |
| Router / TanStack Query | 7.18.4 / 5.104.3 | Browser routing and server state; no custom fetch caching framework. |
| Tailwind / Vite plugin | 4.3.3 / 4.3.3 | Existing semantic CSS tokens preserved. |
| Icons | lucide-react 1.57.0 | Consistent maintained icons. |
| Types | @types/react 19.3.0; react-dom 19.3.0; node 24.19.2 | Match runtime majors. |
| Lint | ESLint 10.12.0, @eslint/js 10.0.1, typescript-eslint 8.71.2, hooks 7.1.1, refresh 0.5.7, globals 17.13.0 | Peer ranges checked before lock; no force or legacy-peer-deps installs. |

React Hook Form/Zod, React Flow, charts, Playwright, provider SDK and MCP SDK are selected when their features arrive. They are not dependencies of Phase 1A and no future version is fabricated. Redis/RabbitMQ/Kubernetes remain unnecessary for the foundation.

## Authentication, deployment and CI decisions

Authentication design remains: 10-minute signed access JWT held in memory, seven-day absolute opaque refresh family in HttpOnly cookie, one-use rotation/reuse detection, live family/account check per authenticated request, BCrypt cost 12 subject to measurement, CSRF header and exact Origin checks. Production cookies are Secure/HttpOnly/SameSite=Lax with host-only scope. Development HTTP settings are explicit and separate. Never store refresh tokens in localStorage. These controls ship and are tested in Phase 1B, not simulated in Phase 1A.

Offline bootstrap creates one initial account, with account-level organization.create privilege, no tenant. It is disabled on normal startup, serializes through an installation marker, refuses reuse and never has a web endpoint. Authenticated organization creation atomically grants Owner membership. Invitees get only delegated tenant roles. See ADR 0014.

Compose configuration: frontend at localhost:8088 only, same-origin reverse proxy, backend and PostgreSQL on internal service DNS, named database volume, no published database/backend ports. Health checks distinguish liveness/readiness. No hardcoded password; required environment variables and .env.example with empty secrets. Fixed digest base images, non-root application containers, graceful shutdown and restart policies. Compose CLI observed 2.39.1; require Compose 2.24+ for the documented features.

CI: pull requests/push run Java build, required PostgreSQL Testcontainers checks, frontend npm ci/typecheck/lint/tests/build and container smoke. Actions pinned to reviewed commit SHAs, read-only workflow permissions, timeouts and cancellation. Publishing/deployment remain separate disabled/configured steps; no deployment claim from a workflow file.

## Authoritative sources

- [Boot requirements](https://docs.spring.io/spring-boot/system-requirements.html) and [managed coordinates](https://docs.spring.io/spring-boot/appendix/dependency-versions/coordinates.html).
- [Maven stable release history](https://maven.apache.org/docs/history.html) and [Central release metadata](https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/maven-metadata.xml).
- [springdoc compatibility](https://springdoc.org/#what-is-the-compatibility-matrix-of-springdoc-openapi-with-spring-boot).
- [Node releases](https://nodejs.org/en/about/previous-releases), [official checksums](https://nodejs.org/dist/v24.22.0/SHASUMS256.txt), [Vite engine requirements](https://vite.dev/guide/), [React versions](https://react.dev/versions) and npm package manifests/peer dependencies.
- [PostgreSQL supported versions](https://www.postgresql.org/support/versioning/).

Recheck patch/security support when upgrading. Record changed choices in the lock/ADR rather than silently using latest. No compatible-stack claim precedes actual build/test results.
