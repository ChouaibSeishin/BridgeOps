# Proposed system architecture

Status: architecture retained after review. Phase 1A implements the technical application and persistence foundation; business modules below remain planned. Start with one Spring Boot deployment, one React frontend and PostgreSQL. Bounded workers run in the backend process when Phase 3 introduces execution. Separate workers and a broker require evidence and an ADR update.

## Context and containers

```mermaid
flowchart LR
  User[Organization user] --> Browser[React browser client]
  Client[Compatible MCP client] --> Edge[Same-origin reverse proxy and TLS]
  Sender[Webhook sender] --> Edge
  Browser --> Edge
  Edge --> App[Spring Boot application]
  App --> DB[(PostgreSQL)]
  App --> Secret[Secret store adapter]
  App --> Remote[Allowlisted external APIs]
  App --> LLM[Optional external AI provider]
```

The browser uses relative `/api/v1` URLs. Vite proxies them in development; the production asset server proxies them to the backend. Container hostnames remain server-side. Detailed management endpoints are private. MCP is disabled until authenticated transport exists.

## Module boundaries

Each module owns entities, repositories and migrations. Callers use public application contracts with DTOs and typed errors. No cross-module entity/repository imports or ORM navigation. Public services enforce authorization even when called from workers, AI or MCP. A small shared kernel holds IDs, safe errors, clock and immutable actor/organization context; it depends on no business module.

| Module | Owns | Permitted dependencies |
| --- | --- | --- |
| identity | Accounts, credentials, sessions, reset and verification | audit |
| organizations | Tenants, membership, roles, permissions, invitations | identity public lookup, audit |
| integrations | Catalog, configuration, controlled HTTP, sample support/team capabilities, receipts | organizations, audit, notifications |
| workflows | Drafts, validation, published snapshots | integration catalog, organizations, audit |
| executions | Runtime states, work, attempts, recovery, operation progress | workflows, integrations, approvals, organizations, audit |
| approvals | Policies, exact approved intent, decisions, durable decision signals | organizations, notifications, audit |
| ai | Provider abstraction, plans, bounded conversations | authorized integration reads, executions, organizations, audit |
| mcp | Protocol, schemas and tool adapters | integration reads, executions, organizations, audit |
| notifications | In-app notifications, delivery attempts, demo email outbox | organizations, audit |
| audit | Append-only safe event writer and restricted queries | shared only |

Organization and identity contexts are explicitly passed, not inferred from worker thread state. Audit query callers must authorize before access; audit's public query method also requires a verified audit-read capability. Platform grants never imply tenant membership.

Approvals do not depend on executions. A decision writes a durable signal. The execution worker reads signals through approvals' public interface, records a consumption receipt and conditionally resumes its own execution. Normal synchronous module transactions share PostgreSQL; no circular service calls are needed. Notification delivery is recoverable work, never the authoritative execution state.

## Component and module dependency diagram

```mermaid
flowchart TB
  Rest[REST adapters] --> Auth[Identity and organization context]
  Mcp[MCP adapter] --> Auth
  Rest --> Wf[Workflows API]
  Rest --> Ex[Executions API]
  Rest --> Ap[Approvals API]
  Rest --> Ai[AI orchestration]
  Mcp --> Cap[Integration capability API]
  Mcp --> Ex
  Ai --> Cap
  Ai --> Ex
  Ex --> Wf
  Wf --> Catalog[Integration catalog]
  Ex --> Ap
  Ex --> Cap
  Worker[Durable worker] --> Ex
  Worker --> Signals[Approval signal reader]
  Signals --> Ap
  Cap --> Sample[Support and team adapters]
  Cap --> Http[Controlled HTTP adapter]
  Cap --> Notify[Notifications API]
  Ap --> Notify
  Ai --> Provider[AI provider port]
  Provider --> Mock[Deterministic mock]
  Provider --> Real[External provider]
  Auth --> Audit[Audit API]
  Ex --> Audit
  Wf --> Audit
  Ap --> Audit
  Ex --> RuntimeDB[(Execution tables)]
  Wf --> DefinitionDB[(Workflow tables)]
  Ap --> ApprovalDB[(Approval tables)]
```

Authorization and auditing apply to all boundaries, including arrows omitted for readability. Tables share a physical database but retain module ownership. Architecture tests will enforce package dependencies once domain code exists.

## Interview rationale

A modular monolith preserves local transaction guarantees while making boundaries explicit enough to extract later. Microservices would add network failure and distributed coordination before the domain is established. Interfaces belong at capability, provider and module boundaries, not on every service.

A distributed transaction tries to coordinate changes in separate systems. PostgreSQL cannot roll back an external API call. BridgeOps persists intent, uses idempotency and reconciles ambiguous outcomes rather than promising global atomicity. Security-sensitive changes write audit records in the same local transaction; notification delivery is asynchronous and recoverable.

Implemented Phase 1D clarification: shared.mail owns the narrow OutboundEmail port and authenticated TLS adapter. Identity and organizations depend on that module-neutral contract; identity does not depend on organization persistence or invitation infrastructure. Member display labels are resolved through identity's public query DTO boundary only after tenant authorization. Planned notifications/workflow modules above remain later work.
