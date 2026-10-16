# Phase 2 — workflow definitions

Status: registry/validator/canonicalizer, persistence and HTTP lifecycle implemented; browser UI/release gates in progress. Phase 1D baseline is 11aec6e. Phase 2 stores definitions only. Published definitions do not execute until Phase 3; approval processing remains Phase 4. No Run/Test Run, connector, webhook, scripting, SQL, AI or MCP capability is introduced.

## Graph v1 and registry

Definition shape: `{schemaVersion:1,nodes:[{id,type,typeVersion:1,config}],edges:[{source,target}]}`. IDs match `[A-Za-z][A-Za-z0-9_-]{0,63}`. Edges mean a single `next` control-flow connection; they carry no data mapping or expressions. Array order is presentation, not execution order. The initial semantics are one connected linear chain, exactly one manual trigger and one end; no forks, joins, branches, loops or disconnected nodes. Later semantics require a versioned schema change.

| Type v1 | Allowed configuration | Definition support |
| --- | --- | --- |
| manualTrigger | `{}` | Manual entry, no input schema or external effects |
| syntheticAction | `{operation:"record",message:string}` | Bounded synthetic label, 1–500 characters; no connector/script/URL |
| approval | `{prompt:string}` | Bounded review description, 1–500 characters; no authority or approval decision |
| end | `{}` | Terminal definition node |

All types are definition-only. Runtime dispatch is absent. The first planned business chain is manualTrigger → syntheticAction → approval → syntheticAction → end. Graph validation rejects unknown keys/types/versions, malformed config/IDs, duplicate IDs/edges, bad references, cycles, missing/multiple trigger/end, unreachable nodes and unsupported degrees/connections. Maximum 50 nodes, 100 edges, 128 KiB UTF-8 definition and 256 KiB HTTP request; JSON depth and string/number lengths are bounded before tree construction. Invalid but bounded draft definitions can be saved; publication requires a valid persisted graph. No raw secret belongs in any definition.

Validation returns `{valid,errors:[{nodeId,path,code,message}]}` with fixed safe actionable messages and deterministic ordering. Paths identify graph fields/node configuration. Errors never echo submitted values or exception details.

## Persistence and publication

V9 is the next migration after inspected V8. Workflow owns tenant ID, stable UUID, name (1–120), description (0–2000), ACTIVE/ARCHIVED state, current published pointer and timestamps. One draft per workflow owns bounded definition JSON and nonnegative revision. Metadata edits are draft mutations and increment the same revision. Published versions own UUID, tenant/workflow IDs, monotonic workflow-local number, source draft revision, schema version, canonical JSON, SHA-256 hash, actor and UTC timestamp. Node type/version data is retained in the canonical definition. Composite foreign keys bind draft/version/current pointer to the same tenant and workflow.

Canonicalization version 1 sorts object keys lexically, nodes by ID and edges by source then target, preserves string content and writes compact UTF-8 JSON with integer schema/type versions. SHA-256 is lowercase hex over those exact bytes. This is a documented BridgeOps format, not a claim of RFC 8785 conformance. Presentation coordinates/labels live outside the definition; this first form editor persists no separate canvas coordinates. Published name/description are frozen metadata alongside the snapshot, outside the executable hash. Draft reload retains submitted array order; canonical publication may reorder arrays without changing graph meaning.

Write lock order is live account → organization → workflow. Organization serialization follows existing membership mutation patterns so permission changes cannot race publication. Publication checks live membership/permission, expected revision and nonarchived state, validates the persisted definition, allocates the next version, inserts the immutable row, updates the pointer, increments the draft revision and writes audit in one short transaction. The increment consumes the published revision; concurrent publication or edit using that revision yields 409. No external work occurs in this transaction. Required audit failure rolls back all publication writes.

Archive increments revision and preserves all drafts/versions; historical reads remain available to current authorized members, writes reject archived state. Duplicate copies the current persisted draft and metadata into an independent new workflow/draft revision 0, never aliases mutable state or copies publication history. Source expectedRevision prevents copying an unintended concurrent edit; archived sources may be duplicated by authorized editors.

Published rows deny runtime UPDATE/DELETE and have a database mutation trigger. Runtime grants are column-scoped for workflow/draft updates, never grant schema/permission/account writes. Existing role templates already supply workflow.read to all tenant roles, edit/publish to Owner/Admin/Builder; Operator/Approver/Viewer remain read-only. No grant expansion or role editor is needed.

## Public module and HTTP contracts

Workflows depends on identity/organization public authorization and a narrow safe audit writer, never their repositories/entities. Public services enforce authorization as well as controllers. Tenant/nonmember/foreign resources are concealed with 404; current member without an operation permission gets 403; stale/repeated publication gets 409; invalid publication gets 422 with validation details; malformed/oversized request gets 400/413; missing identity gets 401. All mutations retain exact Origin and signed CSRF.

Let `W=/api/v1/organizations/{organizationId}/workflows`.

| Method/path | Request / result | Permission |
| --- | --- | --- |
| GET W | Bounded page/size (1–100); stable updated-desc/id order, active and archived workflows | workflow.read |
| POST W | `{name,description,definition}` → 201 draft detail/Location | workflow.edit |
| GET W/{id} | Workflow metadata, draft definition/revision and current published summary | workflow.read |
| PATCH W/{id} | `{expectedRevision,name,description,definition}` → updated detail | workflow.edit |
| DELETE W/{id} | Quoted numeric If-Match → 204 archive, never physical delete | workflow.edit |
| POST W/{id}/validate | `{expectedRevision}` → validation of that persisted draft | workflow.read |
| POST W/{id}/publish | `{expectedRevision}` → 201 immutable version/Location; invalid semantic 422 | workflow.publish |
| POST W/{id}/duplicate | `{expectedRevision}` → 201 independent draft detail/Location | workflow.edit |
| GET W/{id}/versions | Bounded version-number-desc page | workflow.read |
| GET W/{id}/versions/{versionId} | Exact read-only canonical snapshot, metadata and hash | workflow.read |

UI uses existing session client/TanStack Query and design tokens: tenant list, metadata/node/connection forms, saved/unsaved revision, explicit persisted validation, revision-confirmed publication, history/exact snapshot, duplication and archive confirmation. No active execution controls. Permission hints come from current O/access, never infer authority from role names.

Implemented DTOs: Detail is `{workflow,definition,currentPublishedVersion}`. Workflow summary is `{id,name,description,status,revision,currentPublishedVersionId,updatedAt}`. Snapshot is `{version,definition}`. Version summary is `{id,workflowId,versionNumber,sourceRevision,schemaVersion,canonicalizationVersion,name,description,definitionHash,publishedBy,publishedAt}`. Collections contain summaries only, never full graphs. Required create/save description may be an empty string. Unknown DTO fields are 400; graph semantic errors may remain in bounded drafts. Invalid publication returns safe error code INVALID_WORKFLOW with `details:{valid:false,errors:[...]}`. GET detail/exact version returns historical metadata alongside the appropriate graph, never persistence entities. HTTP body filtering after authentication bounds direct/chunked bodies to 256 KiB and parser depth 32/string length 4096/number length 20 with duplicate-key rejection. Draft bounds additionally enforce depth 16, 4096 JSON values, 2000-character strings, 128 KiB, 50 nodes and 100 edges.

## Verification and progress

HTTP increment: all ten restricted-runtime transport cases and 29 selected validator/boundary unit cases passed. They cover lifecycle shapes, persisted validation/422 details, safe errors, stale revisions, duplicate/archive/history, live authentication/permissions, exact Origin/CSRF, every tenant resource family, direct request/parser bounds, audit rollback, implemented OpenAPI and denied execution/test-run/approval routes. The architecture test checks workflow source imports use only its own module, shared primitives and public identity/organization/audit application APIs. Persistence increment 891cf82 is pushed/ref-verified and [hosted CI passed](https://github.com/ChouaibSeishin/BridgeOps/actions/runs/38946336182). No full Phase 2 UI/browser/release completion is claimed.

Persistence increment: V9 and public application lifecycle implemented. Twelve focused restricted-runtime cases passed (10 lifecycle + 2 migration): fresh schema, V8 upgrade preserving existing user/tenant/marker/grants, revision save/reload, invalid publication, immutable metadata/graph/hash, independent copy/archive, live reader/editor/publisher separation, direct service tenant concealment, two publication races, required audit rollback, database mutation denial and composite pointer/child constraints. Runtime remains bridgeops_app; migration/operator fixtures are separate and disposable. No workflow HTTP endpoint is enabled yet. No local Compose migration/account mutation has been performed by these tests.

First increment 4875c64 reconciled stale Git/Phase 1/current-task documentation and accepted this contract; [its hosted workflow passed](https://github.com/ChouaibSeishin/BridgeOps/actions/runs/38943543050). GraphDefinition now implements the bounded registry, deterministic graph validation and canonical hash. The final focused 28-case unit run passed, including save bounds and integer-overflow version rejection. Remaining gates: V8 upgrade/empty-schema restricted-runtime integration tests, HTTP tenant/permission/concurrency/audit/immutable-row tests, frontend tests and required real Chromium. Missing infrastructure fails, not skips. Final gates remain README frontend checks + Maven verify + docs/secret scans + Compose build/start + API smoke + inspected hosted CI. No full Phase 2 completion is claimed. Local pre-migration inspection: schema V8, one user, zero organizations, consumed bootstrap marker; preserve the existing account, keys, environment and volume.
