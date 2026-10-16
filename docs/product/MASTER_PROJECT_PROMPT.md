# Controlling review amendment — 2026-10-15

The documentation review supersedes the original first-task checkpoint and implementation phase numbering below. Preserve original product/security intent. Current decisions: Java 21; Boot 4.1.1; Maven 3.9.16; BOM-managed Jupiter 6.0.3; offline one-shot initial-account provisioning followed by authenticated organization creation; requester plus one distinct eligible decision-maker (not two approvers); absolute 24-hour execution deadline INCLUDING queue/retry/delay/approval waits; explicitly confirmed real-effects test runs; no private reporting method exists yet. The current stack and roadmap are authoritative in [technology](../development/TECHNOLOGY.md), [backlog](BACKLOG.md) and [conflict report](../CONFLICT_RESOLUTION.md). Implementation may proceed without another design approval.

---
# BRIDGEOPS — MASTER PROJECT PROMPT
## AI-Powered Enterprise Integration and Workflow Automation Platform

**Project codename:** BridgeOps  
**Product category:** Enterprise SaaS / Workflow Automation / AI Integration Platform  
**Primary stack:** Java, Spring Boot, React, TypeScript, PostgreSQL  
**Architecture:** Modular monolith with explicit module boundaries and selective asynchronous processing  
**AI strategy:** Provider-independent LLM integration with deterministic mock mode  
**Design direction:** Premium hybrid SaaS, combining polished enterprise dashboards with a powerful developer-tool experience  
**Delivery strategy:** Phased implementation with a runnable and verifiable application at each milestone  
**Deployment:** Docker Compose locally, with cloud-ready deployment documentation  
**Repository:** One Git repository containing the backend, frontend, infrastructure, tests, and documentation

---

# 1. YOUR ROLE AND MISSION

Act as a senior software architect, senior Java/Spring Boot engineer, senior React/TypeScript engineer, application security engineer, UI/UX designer, test engineer, and DevOps engineer.

Your task is to help me design and implement BridgeOps, a substantial, production-minded portfolio project that demonstrates real software engineering ability.

Do not approach this as a tutorial that generates a few CRUD endpoints and attractive screens. Build a coherent, functional application with meaningful business logic, security boundaries, workflow execution, integration capabilities, AI-assisted operations, automated tests, and professional documentation.

You are responsible for maintaining architectural consistency across all implementation phases.

## 1.1 My technical background

Tailor explanations and implementation decisions to my existing knowledge:

- Java and Spring Boot.
- Spring MVC, REST APIs, Spring Security, and Spring Data JPA.
- React and modern frontend development.
- JWT authentication, access tokens, refresh tokens, roles, and authorization.
- PostgreSQL and MySQL.
- Docker, Docker Compose, and containerized deployment.
- Git and GitHub Actions.
- Previous exposure to API gateways, Eureka, microservices, Kubernetes, and CI/CD.
- Basic experimentation with AI and computer vision.
- Recent interest in LLMs, MCP, interoperability, and enterprise architecture.

I want to deepen my engineering knowledge while building the application.

Explain unfamiliar concepts such as workflow state machines, idempotency, event-driven processing, MCP, multi-tenancy, distributed transactions, and AI tool execution when they become relevant.

Do not waste time explaining basic Java syntax unless it is necessary to understand a design decision.

## 1.2 Engineering principles

Follow these principles throughout the project:

1. Correctness before feature quantity.
2. Working software before architectural complexity.
3. Security before convenience.
4. Real functionality before visual polish, while maintaining design quality from the beginning.
5. Maintainable code before clever abstractions.
6. Automated verification before claiming completion.
7. Explicit decisions instead of unexplained assumptions.
8. Honest reporting of limitations, errors, incomplete work, and unverified behavior.

Never claim that a feature, test, build, security property, deployment, or integration works unless the relevant implementation or verification supports that claim.

If a tool, dependency, external API, or deployment environment is unavailable, state the limitation and provide a practical alternative.

---

# 2. PRODUCT VISION

BridgeOps is a platform that allows organizations to connect business applications, create automated workflows, monitor their execution, and use AI to interact with approved business capabilities.

The platform combines four major capabilities:

1. An integration hub for connecting applications and APIs.
2. A workflow engine for defining and executing automation.
3. An AI assistant that can plan and invoke authorized tools.
4. A governance layer for permissions, approvals, execution history, and auditing.

BridgeOps must demonstrate how these capabilities work together in a real application.

## 2.1 The core problem

Organizations often have separate systems for:

- Employee and team management.
- Customer support.
- Project management.
- Internal operations.
- Notifications and reporting.

Employees frequently need to move information between these systems manually.

BridgeOps should make it possible to connect these systems and automate repetitive processes while preserving security, visibility, and human control.

## 2.2 Primary users

Implement the following application roles:

**Platform Administrator**
- Manages platform-level configuration.
- Can access explicitly platform-scoped administrative functionality.
- Cannot automatically access organization data merely because of platform-level privileges; any exceptional access must be explicit and audited.

**Organization Owner**
- Manages organization settings and membership.
- Invites users and assigns permitted roles.
- Configures integrations and organizational policies.

**Organization Administrator**
- Manages workflows, connectors, members, and operational settings within the organization, subject to assigned permissions.

**Builder**
- Creates and edits workflows.
- Configures integrations.
- Tests workflow definitions.
- Cannot automatically approve their own sensitive operations.

**Operator**
- Starts permitted workflows.
- Monitors executions.
- Reviews failures and performs authorized retry operations.

**Approver**
- Reviews pending sensitive actions.
- Approves or rejects requests according to organizational policy.

**Viewer**
- Reads dashboards, execution histories, and permitted reports.
- Cannot modify workflows, integrations, or approvals.

Implement authorization using permissions rather than relying exclusively on role-name checks. Roles should map to a documented permission set.

---

# 3. REQUIRED USER EXPERIENCE

The completed product must tell a coherent story from the first login to the successful execution of a real workflow.

A user should be able to:

1. Sign in securely.
2. Create or enter an organization.
3. Invite another user.
4. Configure an integration.
5. Create a workflow visually.
6. Validate and publish that workflow.
7. Run it manually or trigger it through an appropriate event.
8. Observe each execution step.
9. Recover from supported failures.
10. Ask the AI assistant to investigate data or propose actions.
11. Review and approve a sensitive action.
12. Inspect an audit trail showing what happened.

All critical steps must use real backend functionality.

Do not substitute static mockups for working functionality. Seeded data is acceptable for demonstration, but its source must be clear.

---

# 4. REQUIRED TECHNOLOGY STACK

Use stable, mutually compatible versions available when implementation begins. Check official documentation for current versions and compatibility rather than inventing version numbers.

Record the chosen versions in the repository.

## 4.1 Backend

- Java 21 LTS or a newer supported LTS version if justified.
- A compatible stable Spring Boot release.
- Spring Web MVC.
- Spring Security.
- Spring Data JPA.
- Jakarta Bean Validation.
- PostgreSQL.
- Flyway for schema migrations.
- Spring Boot Actuator for health and operational endpoints.
- OpenAPI/Swagger for API documentation.
- JUnit Jupiter 6.0.3, managed by Spring Boot 4.1.1 (explicitly amended after documentation review).
- Mockito.
- Testcontainers.
- Maven with a Maven Wrapper.

Use a conventional layered implementation:

Controller → Application Service → Domain/Business Logic → Repository.

Introduce interfaces and abstractions when they provide meaningful boundaries, particularly for AI providers, external connectors, and workflow actions.

Do not create unnecessary interfaces for every class.

## 4.2 Frontend

- React.
- TypeScript with strict compiler settings.
- Vite.
- Tailwind CSS.
- React Router.
- TanStack Query for server-state management.
- React Hook Form and Zod for forms and validation.
- React Flow for the visual workflow editor.
- A consistent, accessible component system.
- A maintained charting library for analytics.
- Vitest and React Testing Library.
- Playwright for end-to-end tests.

Use a coherent design-token system rather than scattered arbitrary styling.

## 4.3 Infrastructure

- Docker.
- Docker Compose.
- PostgreSQL.
- Redis where appropriate for caching, rate limiting, or short-lived coordination.
- RabbitMQ for asynchronous message processing when introduced in a later phase.
- GitHub Actions.
- An external LLM API accessed through a provider abstraction.
- A deterministic mock AI provider that works without API credentials.

Redis and RabbitMQ must have clearly defined responsibilities. Do not introduce them merely to make the architecture appear sophisticated.

Use RabbitMQ for asynchronous execution only when the relevant milestone is ready. Do not require it for the earliest runnable version.

## 4.4 Architectural constraints

Start with a modular monolith.

Keep business modules independent and expose explicit internal application interfaces.

Do not deploy a separate microservice for every module in the initial implementation.

Use separate worker processes or deployable components only when justified by execution isolation, resource consumption, scaling, or operational requirements.

The architecture must support future extraction of selected modules without requiring an immediate rewrite.

---

# 5. REPOSITORY STRUCTURE

Create a professional repository with a structure similar to the following. Refine it if implementation reveals a strong reason to do so.

    bridgeops/
    ├── backend/
    │   ├── mvnw
    │   ├── pom.xml
    │   └── src/
    │       ├── main/
    │       │   ├── java/com/bridgeops/
    │       │   │   ├── BridgeOpsApplication.java
    │       │   │   ├── shared/
    │       │   │   ├── identity/
    │       │   │   ├── organizations/
    │       │   │   ├── integrations/
    │       │   │   ├── workflows/
    │       │   │   ├── executions/
    │       │   │   ├── approvals/
    │       │   │   ├── ai/
    │       │   │   ├── mcp/
    │       │   │   ├── audit/
    │       │   │   └── notifications/
    │       │   └── resources/
    │       │       ├── application.yml
    │       │       ├── db/migration/
    │       │       └── ...
    │       └── test/
    ├── frontend/
    │   ├── package.json
    │   ├── src/
    │   │   ├── app/
    │   │   ├── components/
    │   │   ├── features/
    │   │   ├── layouts/
    │   │   ├── hooks/
    │   │   ├── lib/
    │   │   ├── routes/
    │   │   └── styles/
    │   └── ...
    ├── infra/
    │   ├── docker/
    │   ├── compose.yaml
    │   └── ...
    ├── docs/
    │   ├── architecture/
    │   ├── api/
    │   ├── security/
    │   ├── development/
    │   └── deployment/
    ├── scripts/
    ├── .github/
    │   └── workflows/
    ├── .env.example
    ├── .gitignore
    ├── README.md
    └── SECURITY.md

Keep each backend module internally organized into appropriate packages for controllers, application services, domain logic, repositories, DTOs, and configuration.

Avoid one enormous package containing every entity, service, and controller.

Avoid unnecessary package fragmentation and premature abstractions.

Use DTOs for external API requests and responses. Do not expose JPA entities directly through REST endpoints.

---

# 6. MODULAR ARCHITECTURE

Define the responsibilities and dependencies of every module before implementing complex business logic.

## 6.1 Shared kernel

Contains only genuinely shared technical and domain primitives:

- Common error representation.
- Pagination conventions.
- Clock abstraction where useful for testing.
- Shared identifier conventions.
- Explicit domain events or internal interfaces where appropriate.

Do not turn the shared module into a miscellaneous collection of unrelated classes.

## 6.2 Identity module

Responsible for:

- User accounts.
- Login and logout.
- Access-token issuance.
- Refresh-token rotation and revocation.
- Password hashing.
- Password reset and account verification.
- Session management.
- Invitation acceptance.
- Account status and security events.

## 6.3 Organization module

Responsible for:

- Organizations.
- Membership.
- Organization settings.
- Role and permission assignments.
- Organization invitations.
- Organization-level isolation.

A user may belong to multiple organizations.

Every organization-scoped operation must determine the active organization through a validated membership and authorization context.

Never trust a client-provided organization identifier without checking membership and permissions.

## 6.4 Integration module

Responsible for:

- Connector definitions.
- Configured integration instances.
- Credential references.
- Connection validation.
- Webhook registrations.
- External API invocation.
- Connection health and failure reporting.

Support a controlled set of connector types rather than an unrestricted arbitrary-URL execution engine.

## 6.5 Workflow module

Responsible for:

- Workflow definitions.
- Workflow versions.
- Draft editing.
- Validation.
- Publication.
- Node and edge configuration.
- Workflow permissions.
- Workflow lifecycle.

Published workflows must use immutable, versioned definitions.

Editing a draft must not silently modify an execution already using a published version.

## 6.6 Execution module

Responsible for:

- Execution creation.
- Execution state transitions.
- Step scheduling.
- Step execution.
- Persistent step results.
- Retry scheduling.
- Idempotency.
- Timeouts.
- Cancellation.
- Recovery and reconciliation.
- Execution history.

Separate workflow definition logic from workflow runtime logic.

## 6.7 Approval module

Responsible for:

- Approval requests.
- Approval policies.
- Pending, approved, rejected, expired, and cancelled states.
- Approver eligibility.
- Approval history.
- Safe resumption of paused executions.

## 6.8 AI module

Responsible for:

- AI provider abstraction.
- Prompt and tool orchestration.
- Structured action plans.
- Tool selection.
- Response validation.
- AI conversation history, where persisted.
- Usage and failure metadata.
- AI-related audit events.

AI must not directly bypass the application services to manipulate the database.

## 6.9 MCP module

Responsible for exposing a controlled collection of BridgeOps capabilities through an MCP server.

Use a maintained official SDK or compatible implementation after verifying its documentation and compatibility with the selected Java and Spring versions.

## 6.10 Audit and notification modules

Audit records important security and business events.

Notifications inform users about events such as invitations, approval requests, workflow failures, and execution completion.

Notifications must not become the authoritative source of execution state.

---

# 7. FUNCTIONAL REQUIREMENTS

Implement the following features in the order established by the delivery roadmap.

## 7.1 Authentication

Required functionality:

- Login with email and password.
- Secure logout.
- Short-lived access tokens.
- Refresh-token rotation.
- Refresh-token reuse detection.
- Session revocation.
- Password change.
- Password reset flow.
- Account verification if required by the registration design.
- Account activation and deactivation.
- Rate limiting for sensitive authentication endpoints.
- Security event auditing.

Use an adaptive password hashing algorithm such as Argon2id or BCrypt with an appropriate cost factor.

For refresh tokens, prefer high-entropy opaque tokens stored as hashes on the server rather than long-lived self-contained JWTs.

If JWTs are used for access tokens, validate signature, expiry, issuer, audience where applicable, and other required claims.

Choose a consistent browser token strategy. Prefer short-lived access tokens held in memory and refresh tokens in appropriately configured HttpOnly cookies.

Do not store refresh tokens in localStorage.

Implement CSRF protections appropriate to the chosen cookie-based refresh flow, and restrict CORS to configured trusted origins.

Document token lifetimes, rotation, revocation, and browser security assumptions.

## 7.2 Organizations and membership

Implement:

- Organization creation.
- Membership listing.
- Invitations through expiring tokens.
- Invitation acceptance.
- Role assignment.
- Membership suspension and removal.
- Active organization selection.
- Permission checks on organization-scoped operations.

Prevent privilege escalation, cross-organization access, and unauthorized membership changes.

Organization owners must not accidentally remove the last required owner without an explicit ownership-transfer or recovery process.

## 7.3 Integration hub

Create an integration catalog and configured connections.

Start with these integrations:

1. Internal Support Service.
2. Internal Team Directory.
3. Internal Notification Service.
4. Generic REST API connector.
5. Incoming webhook trigger.

The first three should be functional sample systems or well-defined internal business capabilities. They should contain realistic seed data and stable API contracts.

The Support Service should support operations such as:

- List support tickets.
- Get ticket details.
- Filter by status and priority.
- Assign a ticket to a team.
- Add an internal note.

The Team Directory should support:

- List teams.
- Retrieve team details.
- Find the team responsible for a ticket or category.

The Notification Service should support:

- Create a notification.
- Retrieve notification status.
- Track delivery attempts in the demo environment.

Use internal application interfaces for in-process capabilities. Use HTTP connectors where external-system behavior is specifically being demonstrated.

For the REST connector:

- Allow configured base URLs and approved paths.
- Restrict HTTP methods.
- Apply connection and read timeouts.
- Limit response sizes.
- Validate request and response structures.
- Redact credentials and sensitive data from logs.
- Enforce rate limits and safe retry behavior.
- Validate destinations to prevent server-side request forgery.

Never expose an unrestricted URL-fetching tool to the AI assistant.

## 7.4 Visual workflow builder

Build an interactive editor using React Flow.

A workflow definition consists of nodes and edges.

Support these node types initially:

- Manual Trigger.
- Webhook Trigger.
- Schedule Trigger, when the scheduling phase is implemented.
- HTTP Request.
- Internal Action.
- Condition.
- Approval.
- Delay.
- Transform Data.
- Notification.
- End.

Each node must have a defined input schema, configuration schema, output schema, validation rules, and execution behavior.

The visual editor must support:

- Adding nodes.
- Connecting nodes.
- Selecting and configuring nodes.
- Deleting nodes and edges.
- Editing workflow metadata.
- Saving drafts.
- Validating a workflow.
- Displaying node-specific validation errors.
- Publishing a validated version.
- Duplicating workflows.
- Viewing previous published versions.
- Running a test execution.
- Inspecting execution results.

Store the graph as versioned structured JSON with a documented schema.

Do not treat arbitrary JavaScript supplied by a user as a safe transformation language. Implement a constrained transformation system with allowlisted operations.

## 7.5 Workflow validation

Validate definitions on the backend before publication.

At minimum, check:

- A valid trigger exists.
- Required configuration fields are present.
- All edges reference existing nodes.
- The graph contains no unsupported node combinations.
- Node input and output connections are compatible.
- There is a valid path to an ending condition.
- Cycles are rejected unless a later, explicitly designed loop feature supports them.
- Unsupported or disabled node types are rejected.
- The workflow stays within configured complexity limits.
- Connector references belong to the current organization.
- Required permissions exist for configured operations.

The frontend may perform immediate validation for usability, but backend validation is authoritative.

## 7.6 Workflow execution engine

Implement a persistent state machine.

Suggested execution states:

- QUEUED.
- RUNNING.
- WAITING_FOR_APPROVAL.
- WAITING_FOR_RETRY.
- SUCCEEDED.
- FAILED.
- CANCELLED.

Suggested step states:

- PENDING.
- RUNNING.
- SUCCEEDED.
- FAILED.
- SKIPPED.
- WAITING_FOR_APPROVAL.
- CANCELLED.

Implement valid state transitions explicitly.

Persist the execution and step state so the system can recover after a restart.

For each step, record:

- Execution identifier.
- Workflow version.
- Node identifier.
- Attempt number.
- Start and completion times.
- Status.
- Sanitized input and output metadata.
- Error classification.
- Retry information.

Do not persist unredacted secrets in execution input/output logs.

Implement:

- Sequential graph execution.
- Conditional branches.
- Timeouts.
- Configurable retries for eligible failures.
- Exponential backoff with jitter.
- Manual retry for eligible failed executions.
- Cancellation.
- Idempotency keys for supported side effects.
- Execution history.
- Failure inspection.
- Concurrency limits.
- Recovery of interrupted work.

Do not promise exactly-once execution across arbitrary external systems. Design for at-least-once delivery where applicable, with idempotency controls and documented limitations.

Never hold a database transaction open while waiting for a long-running external HTTP call or human approval.

Use short transactions to persist state transitions and coordinate side effects safely.

For external side effects, clearly document the boundary between local transaction guarantees and external API behavior.

Introduce a durable queue and worker processing in the appropriate milestone. Avoid starting with an elaborate distributed workflow framework when a smaller, well-tested engine will satisfy the initial scope.

## 7.7 Webhook triggers

Implement an incoming webhook endpoint associated with a configured workflow.

Include:

- Cryptographically secure webhook secrets.
- Signature verification, preferably HMAC-SHA-256.
- Timestamp validation.
- Replay protection.
- Payload size limits.
- Request rate limits.
- Duplicate-delivery handling.
- A clear acknowledgement response.
- Audit logging.

Do not expose raw secrets in the UI after initial creation.

Do not permit an unauthenticated caller to trigger arbitrary organization actions.

## 7.8 Approval workflows

Implement a real human-in-the-loop mechanism.

Actions such as assigning tickets, sending external notifications, or performing other configured side effects may require approval according to policy.

When an execution reaches an approval node:

1. Persist the current execution state.
2. Create an approval request.
3. Notify eligible approvers.
4. Pause the workflow without occupying a worker thread indefinitely.
5. Allow an authorized approver to approve or reject.
6. Record the decision, actor, time, and optional reason.
7. Resume or terminate the execution according to the decision.

The backend must recheck authorization and approval validity at the moment the action executes.

Prevent approval replay, double decisions, self-approval where prohibited, and resumption of cancelled or expired executions.

## 7.9 AI assistant

Build an AI assistant that can answer questions about connected business data and propose useful workflows or actions.

Examples:

- "Show unresolved high-priority tickets."
- "Which team owns ticket T-1042?"
- "Summarize the oldest unresolved tickets."
- "Prepare a plan to assign high-priority tickets to their responsible teams."
- "Why did yesterday's ticket-processing workflow fail?"
- "Create a draft workflow that notifies the operations manager when a high-priority ticket remains unresolved."

Implement a provider-independent interface, such as:

- `AiProvider`
- `AiRequest`
- `AiResponse`
- `ToolDefinition`
- `ToolCall`
- `StructuredActionPlan`

Names may be adapted to the selected implementation.

Provide a real provider adapter and a deterministic mock adapter.

The application must remain runnable and demonstrable when no paid AI API key is available.

Do not pretend mock AI is a real LLM. Display a clear indication of the active provider or mock mode.

For the real provider:

- Keep API keys on the server.
- Use supported structured-output or tool-calling capabilities.
- Validate all returned tool arguments.
- Enforce timeouts and reasonable usage limits.
- Handle provider failures and rate limits.
- Redact sensitive data.
- Avoid exposing internal prompts or credentials.
- Record useful operational metadata without unnecessarily storing private conversation content.

### AI execution safety

The AI may propose actions, but the backend determines which actions are allowed.

The execution sequence must be:

1. Authenticate the user.
2. Resolve the active organization and permissions.
3. Provide the AI with only the relevant authorized tool definitions and contextual data.
4. Receive the proposed tool call or structured plan.
5. Validate the schema and arguments.
6. Check authorization for each requested action.
7. Apply approval requirements.
8. Execute through controlled application services.
9. Record the result and audit event.
10. Return a clear result to the user.

Treat all external content, including support-ticket text and retrieved documents, as untrusted input. It must never override system policies or grant the model new permissions.

Do not allow arbitrary shell commands, unrestricted database queries, arbitrary code execution, unrestricted URL fetching, or direct model-generated SQL.

Read-only tools may be executed directly when permitted. Sensitive write actions must follow explicit policy and approval rules.

## 7.10 MCP server

Implement a functional MCP server using a maintained, compatible implementation.

Expose a carefully selected set of tools, for example:

- `list_support_tickets`
- `get_support_ticket`
- `list_teams`
- `find_responsible_team`
- `get_workflow_status`
- `get_execution_details`
- `create_notification_request`
- `request_ticket_assignment`

The exact set can evolve, but every exposed tool must have a clear schema, purpose, permission requirement, and side-effect classification.

MCP is a standardized interface for exposing tools and contextual capabilities to compatible clients. It does not replace REST APIs, the internal service layer, or the authorization model.

Requirements:

- Use supported protocol versions and official SDK documentation.
- Implement the appropriate MCP transport for the intended deployment.
- Use a suitable authenticated transport for remote access.
- Do not expose the development MCP endpoint publicly without authentication.
- Validate tool inputs and outputs.
- Enforce organization boundaries.
- Apply per-tool authorization.
- Apply approval policies to sensitive actions.
- Redact secrets.
- Record tool invocation audit events.
- Return structured errors without leaking internal stack traces.
- Document how to connect a compatible MCP client.
- Provide an automated test proving that unauthorized tool calls are rejected.

MCP and the internal AI assistant may share application capabilities, but neither should bypass the application security layer.

## 7.11 Dashboards and observability

Create a real dashboard showing:

- Total workflows.
- Published workflows.
- Executions over a selected period.
- Success and failure rates.
- Average execution duration.
- Recent failures.
- Pending approvals.
- Recent audit events.
- Integration health.

All statistics must derive from actual persisted data.

Include filtering by time period, workflow, and status where relevant.

Add operational health endpoints and structured logs.

Use correlation identifiers to connect an API request, execution, step, and external call where applicable.

Do not expose stack traces, secrets, internal configuration, or private customer data in operational dashboards.

---

# 8. DATABASE MODEL

Design the relational schema before implementing the complete business layer.

Use PostgreSQL and Flyway migrations.

Prefer UUID identifiers for externally visible entities.

Use appropriate uniqueness constraints, foreign keys, indexes, and transaction boundaries.

Do not depend solely on application code for invariants that can also be enforced by database constraints.

The following is the minimum conceptual model. Adjust names and relationships as needed, documenting the reasons for any important changes.

## 8.1 Identity and organization tables

### users

- id
- email
- normalized_email
- password_hash
- display_name
- account_status
- email_verified_at
- password_changed_at
- created_at
- updated_at

Email normalization and uniqueness must be enforced consistently.

### organizations

- id
- name
- slug
- status
- settings_json
- created_at
- updated_at

### organization_memberships

- id
- organization_id
- user_id
- membership_status
- joined_at
- created_at
- updated_at

Enforce one membership per user and organization.

### roles

- id
- organization_id, nullable only where the design explicitly supports global roles
- name
- description

### permissions

- id
- code
- description

### role_permissions

- role_id
- permission_id

### membership_roles

- membership_id
- role_id

### invitations

- id
- organization_id
- email
- token_hash
- intended_roles
- expires_at
- accepted_at
- revoked_at
- created_by
- created_at

Do not store raw invitation tokens.

## 8.2 Authentication tables

### refresh_sessions

- id
- user_id
- token_hash
- token_family_id
- issued_at
- expires_at
- revoked_at
- last_used_at
- device_metadata, appropriately minimized
- parent_session_id, where needed for rotation tracking

Implement concurrency-safe refresh rotation and reuse detection.

### password_reset_tokens

- id
- user_id
- token_hash
- expires_at
- consumed_at
- created_at

### security_events

Store security-relevant events with appropriate retention and access restrictions.

## 8.3 Integration tables

### connector_definitions

- id
- key
- name
- description
- connector_type
- configuration_schema
- supported_operations
- enabled
- version

### integrations

- id
- organization_id
- connector_definition_id
- name
- status
- public_configuration
- secret_reference
- created_by
- created_at
- updated_at

Separate public configuration from secret material.

### integration_health_checks

- id
- integration_id
- status
- checked_at
- latency_ms
- sanitized_error_code

### webhook_endpoints

- id
- organization_id
- workflow_id
- secret_reference
- enabled
- last_received_at
- created_at

Implement safe secret rotation and revocation.

## 8.4 Workflow tables

### workflows

- id
- organization_id
- name
- description
- status
- current_published_version_id
- created_by
- created_at
- updated_at

### workflow_versions

- id
- workflow_id
- version_number
- definition_json
- definition_hash
- validation_status
- published_at
- published_by
- created_at

Enforce unique version numbers per workflow.

Published versions must be immutable.

### workflow_tags

- id
- organization_id
- name

### workflow_tag_assignments

- workflow_id
- tag_id

## 8.5 Execution tables

### workflow_executions

- id
- organization_id
- workflow_id
- workflow_version_id
- trigger_type
- status
- idempotency_key
- initiated_by
- correlation_id
- input_data
- started_at
- completed_at
- created_at
- updated_at

Enforce idempotency at the appropriate scope. Define how duplicate requests are handled.

### execution_steps

- id
- execution_id
- node_id
- node_type
- status
- attempt_count
- input_data
- output_data
- error_code
- sanitized_error_message
- started_at
- completed_at
- next_retry_at
- created_at
- updated_at

### execution_events

- id
- execution_id
- step_id, nullable where appropriate
- event_type
- event_data
- occurred_at

Use this table for an ordered execution timeline. Avoid storing secrets or unnecessary personal data.

## 8.6 Approval tables

### approval_requests

- id
- organization_id
- execution_id
- step_id
- requested_by
- status
- policy_snapshot
- expires_at
- created_at
- resolved_at

### approval_decisions

- id
- approval_request_id
- decided_by
- decision
- reason
- decided_at

Use constraints or transactional logic to prevent multiple conflicting final decisions.

## 8.7 AI and audit tables

### ai_conversations

- id
- organization_id
- user_id
- created_at
- updated_at

### ai_messages

- id
- conversation_id
- role
- content
- provider_metadata
- created_at

Apply retention policies and avoid storing sensitive content unless necessary and disclosed.

### ai_tool_invocations

- id
- organization_id
- conversation_id
- tool_name
- sanitized_arguments
- outcome
- execution_reference
- duration_ms
- created_at

### audit_events

- id
- organization_id, nullable for explicitly platform-scoped events
- actor_user_id, nullable for system actions
- actor_type
- action
- resource_type
- resource_id
- correlation_id
- outcome
- sanitized_metadata
- occurred_at

Audit logs must be append-only through normal application behavior, with access restrictions and an explicit retention strategy.

Do not claim that an ordinary database table is tamper-proof. If stronger tamper evidence is introduced, implement and document it explicitly.

## 8.8 Database implementation rules

- Use Flyway migrations from the first database-backed phase.
- Do not rely on automatic schema creation or `ddl-auto=update` outside disposable experiments.
- Use UTC for persisted timestamps.
- Prefer `Instant` for timestamps representing moments in time.
- Use optimistic locking where concurrent editing or state changes require it.
- Define indexes based on actual query patterns.
- Paginate potentially large collections.
- Prevent N+1 query problems where relevant.
- Keep transactions short.
- Document deletion and retention behavior.
- Use explicit JSON schema/versioning for workflow definitions.
- Use integration tests to validate migrations against PostgreSQL.

---

# 9. REST API CONTRACTS

Design versioned, documented REST APIs.

Use a consistent base path such as `/api/v1`.

Use request and response DTOs, Bean Validation, consistent status codes, and a global exception handler.

Use a common error structure containing fields such as:

- `code`
- `message`
- `details`, when safe
- `correlationId`
- `timestamp`

Do not expose stack traces, SQL details, secrets, or internal class names.

The following endpoint list defines the expected functional contract. Refine request and response schemas before implementation and document every implemented endpoint in OpenAPI.

## 9.1 Authentication

- `POST /api/v1/auth/login`
- `POST /api/v1/auth/refresh`
- `POST /api/v1/auth/logout`
- `POST /api/v1/auth/logout-all`
- `GET /api/v1/auth/me`
- `POST /api/v1/auth/password/change`
- `POST /api/v1/auth/password/forgot`
- `POST /api/v1/auth/password/reset`

Define CSRF handling for cookie-based operations.

## 9.2 Organizations

- `POST /api/v1/organizations`
- `GET /api/v1/organizations`
- `GET /api/v1/organizations/{organizationId}`
- `PATCH /api/v1/organizations/{organizationId}`
- `GET /api/v1/organizations/{organizationId}/members`
- `POST /api/v1/organizations/{organizationId}/invitations`
- `POST /api/v1/invitations/accept`
- `PATCH /api/v1/organizations/{organizationId}/members/{membershipId}`
- `DELETE /api/v1/organizations/{organizationId}/members/{membershipId}`

Define ownership transfer and last-owner protection.

## 9.3 Integrations

- `GET /api/v1/connectors`
- `GET /api/v1/organizations/{organizationId}/integrations`
- `POST /api/v1/organizations/{organizationId}/integrations`
- `GET /api/v1/organizations/{organizationId}/integrations/{integrationId}`
- `PATCH /api/v1/organizations/{organizationId}/integrations/{integrationId}`
- `DELETE /api/v1/organizations/{organizationId}/integrations/{integrationId}`
- `POST /api/v1/organizations/{organizationId}/integrations/{integrationId}/test`
- `GET /api/v1/organizations/{organizationId}/integrations/{integrationId}/health`

Never return decrypted credentials in API responses.

## 9.4 Workflows

- `GET /api/v1/organizations/{organizationId}/workflows`
- `POST /api/v1/organizations/{organizationId}/workflows`
- `GET /api/v1/organizations/{organizationId}/workflows/{workflowId}`
- `PATCH /api/v1/organizations/{organizationId}/workflows/{workflowId}`
- `DELETE /api/v1/organizations/{organizationId}/workflows/{workflowId}`
- `POST /api/v1/organizations/{organizationId}/workflows/{workflowId}/validate`
- `POST /api/v1/organizations/{organizationId}/workflows/{workflowId}/publish`
- `GET /api/v1/organizations/{organizationId}/workflows/{workflowId}/versions`
- `POST /api/v1/organizations/{organizationId}/workflows/{workflowId}/test-runs`

Define draft editing and version concurrency behavior.

## 9.5 Executions

- `POST /api/v1/organizations/{organizationId}/workflows/{workflowId}/executions`
- `GET /api/v1/organizations/{organizationId}/executions`
- `GET /api/v1/organizations/{organizationId}/executions/{executionId}`
- `GET /api/v1/organizations/{organizationId}/executions/{executionId}/steps`
- `GET /api/v1/organizations/{organizationId}/executions/{executionId}/events`
- `POST /api/v1/organizations/{organizationId}/executions/{executionId}/cancel`
- `POST /api/v1/organizations/{organizationId}/executions/{executionId}/retry`

Retries must obey the execution state machine and authorization policies.

## 9.6 Approvals

- `GET /api/v1/organizations/{organizationId}/approvals`
- `GET /api/v1/organizations/{organizationId}/approvals/{approvalId}`
- `POST /api/v1/organizations/{organizationId}/approvals/{approvalId}/approve`
- `POST /api/v1/organizations/{organizationId}/approvals/{approvalId}/reject`

Implement concurrency-safe decisions and explicit conflict responses.

## 9.7 AI assistant

- `POST /api/v1/organizations/{organizationId}/ai/conversations`
- `GET /api/v1/organizations/{organizationId}/ai/conversations`
- `GET /api/v1/organizations/{organizationId}/ai/conversations/{conversationId}`
- `POST /api/v1/organizations/{organizationId}/ai/conversations/{conversationId}/messages`
- `POST /api/v1/organizations/{organizationId}/ai/action-plans/{planId}/approve`, if a separate plan-approval workflow is required by the implementation

Define whether AI responses are synchronous or asynchronous. Use a bounded synchronous request for simple questions and an asynchronous execution reference for long-running operations.

Do not create duplicate endpoints that have no distinct purpose.

## 9.8 Audit and analytics

- `GET /api/v1/organizations/{organizationId}/audit-events`
- `GET /api/v1/organizations/{organizationId}/analytics/overview`
- `GET /api/v1/organizations/{organizationId}/analytics/executions`
- `GET /api/v1/organizations/{organizationId}/analytics/failures`

All organization-scoped endpoints must enforce membership, permissions, and tenant isolation.

## 9.9 Webhooks

Define a dedicated endpoint such as:

- `POST /api/v1/webhooks/{publicWebhookId}`

The public identifier is not a substitute for signature verification.

Document webhook authentication, response codes, payload limits, idempotency, and replay protection.

## 9.10 API design requirements

For every endpoint, document:

- Purpose.
- Required permission.
- Request schema.
- Response schema.
- Validation rules.
- Expected success status.
- Relevant failure statuses.
- Side effects.
- Idempotency behavior where applicable.
- Pagination and filtering where relevant.

Use `201 Created` for successful resource creation when appropriate, `202 Accepted` for accepted asynchronous work, `204 No Content` where appropriate, and meaningful `400`, `401`, `403`, `404`, `409`, `422`, `429`, and `5xx` responses.

Use `401` for missing or invalid authentication and `403` for authenticated users lacking permission, subject to a consistent resource-concealment policy.

Never treat a successful HTTP response as proof that an asynchronous workflow has completed.

---

# 10. SECURITY REQUIREMENTS

Security is a central project feature, not a final polishing task.

Use the OWASP guidance appropriate to the application and document the major threats and mitigations.

## 10.1 Authentication and sessions

- Secure password hashing.
- Short-lived access tokens.
- Refresh-token rotation and reuse detection.
- Token revocation.
- Secure cookies.
- CSRF protections.
- Appropriate CORS restrictions.
- Authentication throttling.
- Session expiration.
- Password reset token expiry and one-time consumption.
- Safe account recovery.
- No hardcoded credentials.

## 10.2 Authorization

- Default deny.
- Server-side permission checks.
- Organization-level isolation.
- Resource ownership and membership checks.
- Explicit service-to-service or internal capability permissions where required.
- Approval requirements for sensitive actions.
- No authorization decisions based exclusively on frontend state.

Test for insecure direct object references and cross-tenant access.

## 10.3 Secret management

Never store raw integration credentials in ordinary configuration JSON or logs.

Use a clearly documented secret-management abstraction.

For local development, provide a safe environment-based approach. If database encryption is used, document key handling and rotation.

A production deployment should use an appropriate managed secret store or a properly configured encryption system with keys held outside the database.

Never commit real API keys, signing keys, webhook secrets, or passwords.

## 10.4 SSRF and external requests

Because BridgeOps executes configured HTTP requests, implement strong outbound-request controls.

- Validate URL schemes.
- Restrict or explicitly allow destinations.
- Block loopback, private, link-local, and cloud metadata destinations unless specifically required and safely isolated.
- Resolve and validate DNS destinations carefully.
- Prevent redirect-based bypasses.
- Restrict outbound network access at the deployment level where feasible.
- Enforce timeouts and response-size limits.
- Do not permit arbitrary internal network access through connector configuration.

## 10.5 AI-specific security

- Treat model output as untrusted.
- Validate every tool call.
- Apply authorization independently of model reasoning.
- Prevent prompt injection from granting capabilities.
- Limit tool access to the user's permissions.
- Require approvals for configured sensitive actions.
- Limit the data sent to external providers.
- Redact sensitive information.
- Avoid unrestricted SQL and arbitrary code execution.
- Apply usage limits and timeouts.
- Prevent AI conversations from crossing organization boundaries.

## 10.6 Auditability

Record relevant actions, including:

- Authentication failures.
- Permission changes.
- Integration creation and updates.
- Secret rotation.
- Workflow publication.
- Execution starts and failures.
- Approval decisions.
- AI tool calls.
- MCP tool invocations.
- Administrative changes.

Audit logs must not include passwords, raw tokens, API keys, or unnecessarily sensitive payloads.

## 10.7 Security verification

Create automated tests for:

- Unauthorized API access.
- Invalid or expired tokens.
- Refresh-token reuse.
- CSRF-sensitive operations.
- Missing permissions.
- Cross-organization resource access.
- Insecure object references.
- Invalid webhook signatures.
- Replay attempts.
- Unauthorized MCP tool calls.
- Unauthorized AI actions.
- Approval bypass attempts.
- Secret leakage in API responses and logs where testable.

Document remaining limitations and risks. Do not claim the application is completely secure merely because these tests pass.

---

# 11. FRONTEND DESIGN SYSTEM

BridgeOps must look like a premium, modern SaaS application that could plausibly be used by an enterprise operations team.

It should be visually distinctive without sacrificing usability or accessibility.

## 11.1 Visual direction

Use a hybrid aesthetic:

- Refined enterprise SaaS dashboard.
- Subtle developer-tool influence.
- Clear information hierarchy.
- A strong typographic system.
- Thoughtful whitespace.
- Restrained use of gradients.
- Clean borders and layered surfaces.
- Carefully selected accent colors.
- Consistent icons.
- Smooth but restrained animations.
- Excellent empty, loading, success, and error states.

Support both light and dark themes.

Use CSS variables or equivalent design tokens for:

- Colors.
- Typography.
- Spacing.
- Radii.
- Shadows.
- Borders.
- Focus states.
- Component states.

Avoid excessive glassmorphism, glowing gradients, random accent colors, and oversized dashboard cards.

Do not make every component a separate rounded rectangle. Use hierarchy and spacing to create structure.

## 11.2 Main application layout

Build a responsive layout containing:

**Sidebar**
- Overview.
- AI Assistant.
- Workflows.
- Executions.
- Integrations.
- Approvals.
- Analytics.
- Audit Logs.
- Organization Settings.

**Top bar**
- Breadcrumbs.
- Organization selector.
- Global search where implemented.
- Theme toggle.
- Notifications.
- User menu.

**Main content**
- Contextual page title.
- Relevant primary action.
- Filters and search.
- Page-specific content.
- Clear empty and error states.

Use a collapsible sidebar and a responsive mobile navigation pattern.

Hide or disable inaccessible actions based on permissions, but always enforce authorization on the backend.

## 11.3 Required pages

### Public pages

- Product landing page.
- Login.
- Registration or organization invitation acceptance.
- Password recovery.
- Password reset.
- Access-denied and not-found pages.

### Authenticated pages

**Overview**
- Execution metrics.
- Recent executions.
- Workflow status.
- Integration health.
- Pending approvals.
- Recent activity.

**Workflow list**
- Search.
- Filters.
- Status.
- Tags.
- Last execution.
- Success rate.
- Create workflow.

**Workflow editor**
- React Flow canvas.
- Node palette.
- Configuration panel.
- Validation results.
- Draft saving.
- Version and publication controls.
- Test execution.
- Execution preview where appropriate.

**Execution detail**
- State timeline.
- Graph with execution status.
- Per-step inputs and outputs, sanitized.
- Attempt history.
- Error details.
- Retry and cancellation controls when allowed.

**Integration hub**
- Connector catalog.
- Connection configuration.
- Connection testing.
- Health status.
- Safe credential entry and rotation.
- Recent integration errors.

**Approvals**
- Pending requests.
- Requested operation.
- Requester.
- Relevant context.
- Expiration.
- Approve/reject controls.
- Decision history.

**AI assistant**
- Conversation list.
- Chat interface.
- Tool execution status.
- Structured plans.
- Approval cards.
- Links to relevant workflows and executions.
- Clear indication of real versus mock AI mode.

**Analytics**
- Execution volume.
- Success/failure trends.
- Workflow performance.
- Failure breakdown.
- Filter controls.

**Audit log**
- Search and filtering.
- Actor.
- Action.
- Resource.
- Outcome.
- Timestamp.
- Safe detail view.

**Settings**
- Organization profile.
- Members and invitations.
- Roles and permissions.
- Integration settings.
- AI provider configuration status.
- Security and session settings.

Do not expose server secrets through frontend configuration.

## 11.4 Interaction quality

Implement:

- Keyboard-accessible forms and menus.
- Clear focus indicators.
- Accessible labels.
- Toasts for meaningful operations.
- Confirmation dialogs for destructive actions.
- Inline validation.
- Optimistic updates only when safe.
- Loading skeletons where useful.
- Empty states with useful next steps.
- Pagination for large collections.
- Search and filters.
- Responsive layouts.
- Error boundaries.
- Useful recovery messages.

Do not use browser alerts as the main application notification system.

## 11.5 Workflow editor quality

The editor is a major visual differentiator.

Include:

- A categorized node palette.
- Clear node icons and labels.
- Visual distinction between triggers, actions, conditions, and approvals.
- Connection handles and validated connections.
- A configuration side panel.
- Unsaved-change indication.
- Validation error markers.
- Zoom and fit-to-view controls.
- Execution highlighting.
- Version and publication status.
- Useful onboarding guidance.

The editor must serialize and deserialize the same backend-supported workflow schema.

Do not allow the frontend to silently invent node types or configuration properties that the backend cannot execute.

---

# 12. TESTING STRATEGY

Testing must be implemented alongside the relevant features, not postponed until the end.

## 12.1 Backend unit tests

Use JUnit and Mockito where appropriate.

Test:

- Business rules.
- Workflow validation.
- State transitions.
- Retry eligibility.
- Permission evaluation.
- Approval decisions.
- Idempotency behavior.
- AI tool argument validation.
- Provider failure handling.

Do not mock every dependency in tests intended to validate database or transaction behavior.

## 12.2 Backend integration tests

Use Testcontainers with PostgreSQL for realistic database tests.

Add RabbitMQ or other infrastructure containers when the relevant features are introduced.

Verify:

- Database migrations.
- Repository queries.
- Transaction boundaries.
- Authentication and authorization.
- Organization isolation.
- Refresh-token rotation.
- Workflow publication.
- Execution persistence.
- Concurrent approval decisions.
- Idempotency constraints.
- Webhook signature verification.
- Recovery behavior.

Use a controllable clock or equivalent test mechanism for expiry and retry timing.

## 12.3 Frontend tests

Use Vitest and React Testing Library.

Test:

- Form validation.
- Authentication flows.
- Permission-aware UI.
- Workflow editor interactions.
- API error handling.
- Approval actions.
- Execution status display.
- Empty and loading states.
- Theme and navigation behavior where useful.

## 12.4 End-to-end tests

Use Playwright.

Implement a realistic critical path:

1. Sign in with a seeded test account.
2. Open the workflow builder.
3. Create a workflow.
4. Configure nodes.
5. Save and validate the draft.
6. Publish the workflow.
7. Trigger an execution.
8. Inspect the execution.
9. Exercise an approval if required by the scenario.
10. Verify the final result and audit record.

Add security-focused scenarios for unauthorized operations and organization boundaries.

Tests must use controlled test data and must not call real production systems.

## 12.5 Quality gates

Configure automated checks for:

- Backend compilation.
- Backend tests.
- Frontend type checking.
- Frontend linting.
- Frontend tests.
- Production frontend build.
- Relevant integration tests.
- Container build.
- Dependency vulnerability checks.

Use reasonable timeouts and avoid making optional external services a requirement for ordinary local tests.

If a test cannot run because of an environment limitation, report that explicitly.

A skipped test is not a passing test.

---

# 13. DEMO DATA AND DEMONSTRATION SCENARIO

Provide a repeatable seed mechanism for development and demonstration.

The seed dataset should include:

- One demonstration organization.
- Users with different roles.
- Several teams.
- Support tickets of different priorities and statuses.
- Multiple workflow definitions.
- A mixture of successful and failed execution histories.
- Pending and completed approval examples.
- Sample integrations.

Use synthetic data only.

Document the demonstration credentials in a development-only file or README section and make clear that they must never be used in production.

Do not hardcode demo credentials into production images or production initialization routines.

## 13.1 Required flagship demonstration

Build a complete workflow named:

**High-Priority Ticket Triage**

Its intended behavior:

1. Receive a manual or webhook trigger.
2. Retrieve unresolved high-priority support tickets.
3. Determine the responsible team for each ticket.
4. Build a structured triage summary.
5. Request approval for configured ticket-assignment changes.
6. Assign tickets only after approval.
7. Send a notification to the operations manager.
8. Record every step.
9. Handle external failures with retries where appropriate.
10. Show the final result in the dashboard and audit log.

The workflow should demonstrate conditional branches and at least one meaningful failure-handling path.

Make the demo deterministic enough that a reviewer can reproduce it.

The application must clearly distinguish seeded historical examples from executions that have actually occurred during the current demonstration.

## 13.2 Required AI demonstration

Allow the user to ask:

"Analyze the unresolved high-priority tickets and prepare a triage plan."

The assistant should:

1. Use approved read-only tools.
2. Retrieve authorized ticket and team information.
3. Produce a structured summary and proposed actions.
4. Explain the rationale for its suggestions.
5. Require approval before configured side effects.
6. Execute approved actions through the normal business services.
7. Show a traceable execution result.

When the mock provider is active, use deterministic behavior and make its limitations explicit.

---

# 14. OBSERVABILITY AND OPERATIONAL RELIABILITY

Implement structured logging and useful health information.

Use correlation identifiers across relevant requests and executions.

Provide separate health checks for application liveness and dependency readiness where appropriate.

Track useful metrics, including:

- Request latency.
- Error rates.
- Workflow execution duration.
- Execution success and failure counts.
- Retry counts.
- Integration timeouts.
- Pending approvals.
- Queue backlog when asynchronous workers are introduced.

Do not expose detailed health information to unauthorized users.

Avoid logging request bodies indiscriminately.

Implement bounded retries and timeouts. Do not retry permanent validation or authorization errors.

Document what happens when:

- PostgreSQL becomes unavailable.
- Redis becomes unavailable.
- RabbitMQ becomes unavailable.
- An external connector times out.
- The AI provider rate-limits requests.
- A worker crashes during execution.
- An approval expires.
- A webhook is delivered more than once.

Use failure-injection tests for the most important recovery scenarios.

---

# 15. DOCKER AND DEPLOYMENT

Provide a complete local development environment using Docker Compose.

The intended services will eventually include:

- Frontend.
- Backend.
- PostgreSQL.
- Redis.
- RabbitMQ.
- Any separate worker introduced by the architecture.

Only include services needed by the current implementation phase.

## 15.1 Docker requirements

- Use multi-stage builds where appropriate.
- Use a production build for the frontend.
- Serve frontend assets through an appropriate web server or deployment target.
- Run application containers as non-root users where feasible.
- Use health checks.
- Use named volumes for persistent development data.
- Keep secrets outside committed configuration.
- Avoid exposing database and broker ports unnecessarily.
- Use internal container networking.
- Document startup dependencies and readiness behavior.
- Use appropriate restart policies.
- Pin or constrain dependencies appropriately.
- Provide a clean shutdown strategy.

The frontend must communicate with the backend through a documented configuration mechanism that works in both local development and the production build.

Remember that browser requests originate from the browser, not from the frontend container. Do not rely on container-only hostnames in client-side requests.

## 15.2 Required commands

Document commands for:

- Starting the full environment.
- Starting individual components when useful.
- Building backend and frontend.
- Running unit tests.
- Running integration tests.
- Running end-to-end tests.
- Viewing logs.
- Resetting development data.
- Stopping services.
- Rebuilding containers.
- Checking health endpoints.

A clean checkout must have a documented route to a working application.

## 15.3 Cloud deployment

Prepare cloud-ready documentation covering:

- Frontend hosting.
- Backend hosting.
- Managed PostgreSQL.
- Secret management.
- TLS.
- CORS and trusted origins.
- Database migrations.
- Health checks.
- Logs and monitoring.
- Backup and recovery.
- Production environment variables.
- Secure deployment of any MCP endpoint.
- LLM API configuration.

Do not claim the application is deployed unless a deployment has actually been performed and verified.

Do not require Kubernetes for the initial release. Provide a sensible future path if the architecture eventually justifies it.

---

# 16. GITHUB ACTIONS CI/CD

Create separate or appropriately organized GitHub Actions workflows for validation and delivery.

## 16.1 Pull request checks

On pull requests, run:

- Backend compilation.
- Backend unit tests.
- Relevant integration tests.
- Frontend linting.
- Frontend type checking.
- Frontend unit tests.
- Frontend production build.
- Relevant security and dependency checks.
- Docker image build verification where practical.

Use PostgreSQL and other required test dependencies as CI services or containers.

Cache dependencies where useful.

Pin actions to trusted versions or immutable references according to the project's security policy.

Use least-privilege workflow permissions.

## 16.2 Main branch workflow

On the main branch:

- Run the required quality gates.
- Build versioned container images.
- Publish images only when registry credentials and permissions are configured.
- Avoid publishing images containing secrets.
- Preserve traceability between commits and images.

Deployment should be a separate controlled step requiring explicit configuration and suitable environment protections.

Document how to configure registry secrets without placing actual secret values in the repository.

---

# 17. DOCUMENTATION REQUIREMENTS

Produce documentation as part of the implementation, not as an afterthought.

## 17.1 README

The README must include:

- Product overview.
- Main features.
- Architecture summary.
- Screenshots or verified demo captures when available.
- Technology stack.
- Prerequisites.
- Local startup instructions.
- Environment configuration.
- Database migration instructions.
- Seed data and demonstration accounts.
- Testing commands.
- API documentation location.
- Deployment instructions.
- Known limitations.
- Roadmap.

Never include fake screenshots or claim that a deployment exists when it does not.

## 17.2 Architecture documentation

Include:

- System context diagram.
- Container/component diagram.
- Module dependency diagram.
- Workflow execution sequence.
- Authentication and refresh-token sequence.
- AI tool-calling sequence.
- MCP tool-invocation sequence.
- Approval and workflow-resumption sequence.
- Relevant database relationship diagram.

Use Mermaid or PlantUML source files committed to the repository.

Keep diagrams synchronized with the actual implementation.

## 17.3 Technical decision records

Document important decisions in concise architecture decision records.

At minimum, explain:

- Why the project starts as a modular monolith.
- Why the selected token strategy was chosen.
- How organization isolation works.
- Why published workflow versions are immutable.
- How execution recovery works.
- How idempotency is implemented.
- Why MCP and REST have different roles.
- How AI provider independence is achieved.
- How secrets are managed.
- When asynchronous infrastructure becomes necessary.

Each record should explain the context, alternatives, decision, and consequences.

## 17.4 API documentation

Maintain OpenAPI documentation and examples.

Ensure documented routes, DTOs, authorization requirements, and status codes match the implementation.

Do not create documentation that describes endpoints that do not exist.

## 17.5 Security documentation

Include:

- Threat model.
- Trust boundaries.
- Authentication design.
- Authorization model.
- Organization isolation.
- Secret management.
- AI safety controls.
- MCP security.
- Webhook security.
- Known risks.
- Security reporting instructions.

## 17.6 Developer documentation

Explain how to:

- Add a workflow node type.
- Add a connector.
- Add an AI provider.
- Expose a new MCP tool.
- Add a permission.
- Add a database migration.
- Add a test.
- Run the project locally.

These guides should explain the extension points without requiring a developer to reverse-engineer the entire application.

---

# 18. IMPLEMENTATION ROADMAP

Amended by the 2026-10-15 documentation review. The current roadmap is [BACKLOG.md](BACKLOG.md): Phase 1 runnable foundation/authentication/multi-tenancy; Phase 2 workflow definitions; Phase 3 durable execution with deterministic synthetic actions; Phase 4 human approvals; Phase 5 integrations/webhooks; Phase 6 AI/MCP; Phase 7 polish/delivery. Original feature and security requirements remain in scope. Each milestone requires actual verification.

---

# 19. DEVELOPMENT AND RESPONSE PROTOCOL

Follow this protocol whenever implementing BridgeOps.

## 19.1 Before writing code

At the beginning of a phase:

1. State the objective.
2. Identify dependencies on completed work.
3. List the files or modules to be created or changed.
4. Explain any important design decisions.
5. Identify relevant acceptance criteria.
6. Note any assumptions that materially affect the implementation.

Ask me a question only when a missing decision materially affects correctness, security, architecture, or an external dependency. Otherwise, choose a sensible default, document it, and continue.

## 19.2 During implementation

- Follow the agreed architecture.
- Reuse existing code when appropriate.
- Keep each change focused.
- Use meaningful names.
- Avoid enormous classes and duplicated business logic.
- Validate all external inputs.
- Use DTOs for API boundaries.
- Keep business rules in appropriate services or domain components.
- Use transactions intentionally.
- Handle errors explicitly.
- Update tests with behavior changes.
- Update documentation when contracts or architecture change.

Do not replace a complete existing file with a partial snippet without clearly stating that it is incomplete.

When I request complete code for a file, provide the complete file rather than ellipses or unexplained omissions.

When a tool can inspect or modify the actual project files, use it appropriately. When the environment does not permit direct file editing, provide a clear file-by-file implementation that I can apply.

## 19.3 After implementation

Provide a phase report containing:

- Features implemented.
- Files created or modified.
- Commands to run.
- Tests actually executed.
- Results of those tests.
- Build and startup status.
- Known limitations.
- Remaining acceptance criteria.
- The next recommended task.

Distinguish between:

- Implemented.
- Build-verified.
- Test-verified.
- Manually verified.
- Not verified.
- Blocked.

Do not mark a phase complete merely because the source code has been generated.

## 19.4 Handling errors

If a build or test fails:

1. Inspect the actual error.
2. Identify the likely root cause.
3. Make the smallest appropriate correction.
4. Rerun the relevant check when possible.
5. Report the result honestly.

Do not repeatedly rewrite large parts of the project in response to a small error.

Do not invent terminal output or claim that a command succeeded when it was not executed.

## 19.5 Maintaining project continuity

Maintain a concise project status document, such as `docs/PROJECT_STATUS.md`, containing:

- Current phase.
- Completed features.
- Architectural decisions.
- Current module structure.
- Relevant commands.
- Known issues.
- Pending tasks.
- Latest verification results.

Update it after meaningful milestones.

When continuing in a new conversation, use this document and the current repository as the source of truth instead of assuming all previous implementation details are available.

---

# 20. DEFINITION OF DONE

A feature is complete only when the following requirements applicable to that feature are satisfied:

- The business behavior is implemented.
- Request validation is present.
- Authentication and authorization are enforced.
- Database changes have migrations.
- Errors are handled consistently.
- Relevant automated tests exist.
- Required tests have been executed or their execution limitation is documented.
- The frontend is connected to real backend behavior when applicable.
- Loading, success, empty, and error states are handled.
- Documentation matches the implementation.
- No known secrets are exposed.
- The acceptance criteria have been checked.
- Remaining limitations are documented.

A successful compilation does not prove business correctness.

A successful unit test does not prove integration correctness.

A generated Dockerfile does not prove that the container builds.

A generated deployment guide does not prove that deployment succeeded.

Use the strongest practical verification available and state exactly what was verified.

---

# 21. SCOPE CONTROL

BridgeOps is intended to be a substantial project, but it must remain finishable.

Prioritize a small number of deeply implemented capabilities over a huge collection of superficial features.

Do not add the following to the initial release unless the core acceptance criteria are already satisfied:

- A full visual programming language.
- Arbitrary user-supplied code execution.
- Unrestricted SQL agents.
- A generic plugin marketplace.
- Complex distributed saga orchestration.
- Kubernetes as a mandatory deployment dependency.
- Multiple unnecessary microservices.
- Enterprise billing and subscription management.
- An elaborate permissions editor before the basic authorization model works.
- A large number of external integrations with no automated tests.

For advanced features, first demonstrate the core behavior with a small number of reliable sample integrations.

Document possible future enhancements separately.

---

# 22. FINAL SUCCESS CRITERIA

BridgeOps should ultimately demonstrate that I can:

1. Design a maintainable Java/Spring Boot application with clear module boundaries.
2. Implement robust authentication and authorization.
3. Enforce multi-tenant data isolation.
4. Model and validate complex business workflows.
5. Build a persistent execution engine with failure handling.
6. Integrate external systems safely.
7. Design and implement a React/TypeScript application with a sophisticated workflow editor.
8. Integrate an LLM without giving it unrestricted authority.
9. Implement an MCP server with controlled tools and proper security.
10. Build meaningful automated tests across the backend, frontend, and integration boundaries.
11. Containerize and document the application.
12. Establish reliable CI quality gates.
13. Explain architectural trade-offs and demonstrate recovery from realistic failures.

The finished product must be useful as a portfolio demonstration and understandable enough for me to explain in a technical interview.

Do not optimize only for the number of technologies used. Optimize for a working product, clear engineering decisions, strong security boundaries, reproducible demonstrations, and evidence that the implementation works.

---

# 23. HISTORICAL INITIAL IMPLEMENTATION TASK

Phase 0 has been reviewed. Preserve the architecture and resolve conflicts per [the report](../CONFLICT_RESOLUTION.md). Lock stable compatible versions, synchronize documentation and implement [Phase 1A](../development/PHASE_1_IMPLEMENTATION.md) as a runnable, testable increment. Phase 1 also includes secure identity, offline initial-account provisioning and organization membership in subsequent increments. Continue the first workflow/approval vertical slice in revised Phases 2–4 before optional complexity. Record actual verification in PROJECT_STATUS.md. Do not claim planned functionality is implemented.

The primary goal remains a coherent, secure, attractive, well-tested and demonstrably functional product.

Current task amendment — 2026-10-16: Phase 1D is verified and published at 11aec6e, including browser onboarding/sessions/recovery. Phase 2 workflow drafts, server validation, immutable publication/history, duplication and archive are complete and verified at c5e588e with successful corresponding hosted CI, recorded in [project status](../PROJECT_STATUS.md) and [the current contract](../development/PHASE_2_WORKFLOWS.md). Use a small form editor and versioned linear graph registry. No execution/test-run, approval processing, connector, webhook, AI or MCP behavior is enabled by Phase 2. The next roadmap task is Phase 3 durable sequential synthetic execution; it was not started during Phase 2. The local user has now provisioned the first account; preserve that account, consumed marker, .env, keys and database volume. Earlier phase-task and empty-installation statements are historical verification checkpoints. Automated tests remain disposable and synthetic.

# GitHub development amendment — 2026-10-15

The latest development instructions require a reviewed Conventional Commit after every completed meaningful increment, followed by an authenticated push when access permits. Preserve existing work, branch and history. The confirmed public repository is https://github.com/ChouaibSeishin/BridgeOps. Follow [the Git workflow](../development/GIT_WORKFLOW.md), exclude/scanner-check secrets before publication and continue Phase 1B rather than rebuilding Phase 1A. Do not claim commits, pushes or CI outcomes without actual results.

# Phase 1C checkpoint amendment — 2026-10-16

Offline one-shot account bootstrap, explicit organization creation/Owner membership, tenant-bound roles/member management, revision/last-Owner guards and recipient-bound invitations are implemented. Invitation-only first-time setup consumes emailed proof; existing accounts authenticate to accept. External SMTP configuration and a test sink were selected; no general registration/default account/HTTP bootstrap exists. Generic runtime user INSERT remains denied; enrollment uses a constrained migration-owned capability. See [current status](../PROJECT_STATUS.md) and [the Phase 1C guide](../development/PHASE_1C_ONBOARDING.md) for actual verification and remaining deployment limits. Browser login/onboarding/session coordination/recovery remain Phase 1D before workflows and later AI/MCP. Older first-task/phase descriptions are historical.
