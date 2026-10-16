# Dependency and runtime lock evidence

Recorded 2026-10-15 UTC. The committed pom.xml, Maven Wrapper properties and package-lock.json are executable dependency sources. This file records selection evidence; PROJECT_STATUS.md records which checks actually passed.

| Item | Locked value |
| --- | --- |
| Backend parent/BOM | Spring Boot 4.1.1 |
| Compile target | Java 21 |
| Runtime JRE observed | Eclipse Temurin 21.0.12.1+1-LTS |
| Verification host JDK | Oracle 21.0.4 (older local installation; container runs maintained pinned Temurin) |
| Maven distribution | 3.9.16 ZIP; Wrapper 3.3.4 only-script |
| Maven distribution SHA-256 | 5af3b743dd8b876b5c45da33b676251e5f1687712644abb4ee519ca56e1d89ce |
| Maven verification | Downloaded ZIP compared to Apache published SHA-512, then SHA-256 pinned for wrapper |
| Node | 24.22.0 LTS; npm 11.19.1 |
| Node Windows ZIP SHA-256 | f9683a19877e12a9b167305c8db71040d812876359de21f0d2e97c3f321f80c3 |
| Node verification | Compared to official SHASUMS256.txt |
| Frontend packages | Exact package.json direct versions; package-lock v3 resolved transitives/integrity |
| Managed tests/database | Jupiter 6.0.3; Mockito 5.23.0; Testcontainers 2.0.5; Flyway 12.4.0; JDBC 42.7.13 |
| Docker/Compose observed | Engine 28.3.2; Compose 2.39.1 |
| Frontend server observed | nginx 1.30.6 |

## Immutable container references

```text
eclipse-temurin@sha256:e0c60c487345d1dc9d0fc7b6f0496f3cc941e5132e09296cc17a6decc71b902b
eclipse-temurin@sha256:f04fb34e053148344e83317976114ec3f37e4b830ec8bdab5a2fe3cecd7d010b
node@sha256:44fce696f38ba8c987c4db89954051000bbd05ffd8d8659ba15ed4a8602e937f
postgres@sha256:77f585114c32fbca283dc835b0596f4e52b51b4c6662d7810b2f4084f60a1873
nginx@sha256:ed84151ed2d47ef93c0939eb9b9754c6990db6092cd2081e11988a47a83d47ea
```

Selected tag aliases: eclipse-temurin:21-jdk-jammy, eclipse-temurin:21-jre-jammy, node:24.22.0-alpine, postgres:18.6-alpine and nginx:stable-alpine. Dockerfiles/Compose use the digests above rather than resolving those aliases on every build. Runtime image identity is separate from host Java/Node installations. Builder apt installs unzip because Wrapper 3.3.4 otherwise switches ZIP to tar.gz, which would not match the pinned ZIP checksum; checksum verification is retained.

GitHub action releases were queried from their official repositories and resolved to commit SHAs. ci.yml pins checkout v7.0.1, setup-java v6.1.0 and setup-node v7.1.0; the [foundation workflow](https://github.com/ChouaibSeishin/BridgeOps/actions/runs/38776342374) passed on commit b1517ec. Later commits require their own verified CI result. CI Java setup follows the maintained Java 21 line; exact observed runtime must be recorded by each CI run.

No release candidate is selected. TypeScript 5.9.3 intentionally satisfies typescript-eslint's <6.1 peer range. No force/legacy-peer-deps install was used. Later feature SDKs are not yet dependencies. Future upgrades update this evidence and rerun relevant checks. A lock does not establish absence of vulnerabilities.

Phase 1B.2 adds spring-security-oauth2-jose without an explicit version: the unchanged Spring Boot 4.1.1 BOM manages Spring Security and Nimbus. RSA signing/verification uses those maintained APIs rather than a separate JWT library/version override.

Phase 1C adds spring-boot-starter-mail under the unchanged Boot 4.1.1 BOM for the user-selected external SMTP requirement. No explicit mail-library version override, framework/toolchain upgrade or additional infrastructure service is introduced.
