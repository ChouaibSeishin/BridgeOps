# ADR-0009-secret-store: Secret references and controlled egress

Status: Proposed. Date: 2026-10-15.

## Context

Integrations need credentials without exposing them in config or logs.

## Alternatives

Plain JSON secrets; hardcoded keys; arbitrary URLs with shared credentials.

## Decision

SecretStore port with local environment/mounted references; managed storage or reviewed envelope encryption for persistent production credentials. Bind credentials to catalog destinations and operations.

## Consequences

Local setup is simpler but not a vault. Persistent entry, key rotation and SSRF-safe actual connection handling are explicit implementation gates.
