# ADR-0009-secret-store: Secret references and controlled egress

Status: Accepted for Phase 5 implementation, 2026-10-21. Original proposal: 2026-10-15.

## Context

Integrations need credentials without exposing them in config or logs.

## Alternatives

Plain JSON secrets; hardcoded keys; arbitrary URLs with shared credentials.

## Decision

SecretStore port with local environment/mounted references; managed storage or reviewed envelope encryption for persistent production credentials. Bind credentials to catalog destinations and operations.

## Consequences

Local setup is simpler but not a vault. Persistent entry, key rotation and SSRF-safe actual connection handling are explicit implementation gates.

The [Phase 5 contract](../../development/PHASE_5_INTEGRATIONS.md) selects tenant/reference/purpose-bound AES-256-GCM envelopes, versioned mounted master keys, opaque references and fail-closed key availability. Production outbound operations require exact approved hosts and validation in the actual connection resolver with ordinary TLS hostname/SNI checks. There is no production private-network exception. Encryption/address-policy primitives alone do not enable HTTP dispatch or prove external connectivity.
