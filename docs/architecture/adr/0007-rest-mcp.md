# ADR-0007-rest-mcp: REST and MCP as adapters

Status: Proposed. Date: 2026-10-15.

## Context

Browser users and compatible tool clients need different protocols but identical business authority.

## Alternatives

Replace REST with MCP; generate unrestricted tools from every controller.

## Decision

REST is the versioned browser API. Curated authenticated MCP tools call the same application services with explicit schemas, tenant context, permissions and approval policies.

## Consequences

Protocol maintenance is separate from business logic. SDK/transport/auth compatibility is rechecked in Phase 6; no remote anonymous endpoint.
