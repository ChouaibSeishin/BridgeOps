# ADR 0017: Non-root identity for private key mounts

Status: Accepted, 2026-10-26.

## Context

Linux integration key setup deliberately creates an owner-only directory and key (0700/0600). A read-only bind mount retains that host ownership. The image's default non-root application UID may differ from the operator's UID, so a healthy application could still be unable to create or resolve an encrypted integration secret. An isolated synthetic-key probe reproduced unreadability for the default application identity and readability for a matching non-root owner. No real key or database was mounted in the probe.

## Decision

On Linux, start the backend with `BRIDGEOPS_RUNTIME_USER` set to the current non-root operator's numeric UID:GID. Compose supports that explicit process-environment setting; existing `.env` files need no rewrite. Docker Desktop retains the image's default non-root identity and existing host ACL controls. Key modes, directory permissions, encryption, tenant binding and read-only mounts remain unchanged.

The image entrypoint refuses UID 0 before starting Java, including an accidentally configured root runtime. It then execs Java with the existing memory option and forwards application/bootstrap arguments, preserving process signals. The entrypoint is root-owned and non-writable by the application.

CI uses the actual Linux runner UID:GID, checks that the running non-root application can read the 32-byte key but cannot write it, and verifies that a separate network-isolated root invocation exits with code 78. Readiness alone is insufficient evidence of secret-store usability.

## Consequences

Linux operators must supply the UID:GID setting on each Compose startup, as documented in the README. This is an operating-system identity only; it grants no BridgeOps user, organization or workflow authority. Existing credentials, account/bootstrap state, signing material, encrypted envelopes and database volumes are preserved. No file permission is relaxed and no privileged application startup is introduced.
