# ADR-0003-tenant-isolation: Shared database tenant isolation

Status: Proposed. Date: 2026-10-15.

## Context

Users can join multiple organizations; platform roles must not reveal tenant data.

## Alternatives

Database-per-tenant; schema-per-tenant; RLS as initial enforcement.

## Decision

Explicit validated organization context, permission checks, tenant-qualified repositories and composite tenant foreign keys. Platform grants remain separate.

## Consequences

Operationally simple, but every repository path must obey tenancy. No RLS protection is claimed; negative tenant tests are mandatory.
