# Phase 2 — workflow definitions

Status: workflow backend and browser form editor implemented; final real-browser/container/release gates in progress. Phase 1D baseline is 11aec6e. Phase 2 stores definitions only. Published definitions do not execute until Phase 3; approval processing remains Phase 4. No Run/Test Run, connector, webhook, scripting, SQL, AI or MCP capability is introduced.

## Graph v1 and registry

Definition shape: `{schemaVersion:1,nodes:[{id,type,typeVersion:1,config}],edges:[{source,target}]}`. IDs match `[A-Za-z][A-Za-z0-9_-]{0,63}`. Edges mean a single `next` control-flow connection; they carry no data mapping or expressions. Array order is presentation, not execution order. The initial semantics are one connected linear chain, exactly one manual trigger and one end; no forks, joins, branches, loops or disconnected nodes. Later semantics require a versioned schema change.

| Type v1 | Allowed configuration | Definition support |
| --- | --- | --- |
| manualTrigger | `{}` | Manual entry, no input schema or external effects |
| syntheticAction | `{operation:"record",message:string}` | Bounded synthetic label, 1–500 characters; no connector/script/URL |
| approval | `{prompt:string}` | Bounded review description, 1–500 characters; no authority or approval decision |
| end | `{}` | Terminal definition node |

All types are definition-only. Runtime dispatch is absent. The first planned business chain is manualTrigger → syntheticAction → approval → syntheticAction → end. Graph validation rejects unknown keys/types/versions, malformed config/IDs, duplicate IDs/edges, bad references, cycles, missing/multiple trigger/end, unreachable nodes and unsupported degrees/connections. Maximum 50 nodes, 100 edges, 128 KiB UTF-8 definition and 256 KiB HTTP request; JSON depth and string/number lengths are bounded before tree construction. Invalid but bounded draft definitions can be saved; publication requires a valid persisted graph. No raw secret belongs in any definition.

Valid definition example (stores intent only; no action or approval is executed):

```json
{"schemaVersion":1,"nodes":[
  {"id":"start","type":"manualTrigger","typeVersion":1,"config":{}},
  {"id":"prepare","type":"syntheticAction","typeVersion":1,"config":{"operation":"record","message":"Prepare a review plan"}},
  {"id":"review","type":"approval","typeVersion":1,"config":{"prompt":"Review the proposed plan"}},
  {"id":"record","type":"syntheticAction","typeVersion":1,"config":{"operation":"record","message":"Record the approved intent"}},
  {"id":"finish","type":"end","typeVersion":1,"config":{}}
],"edges":[{"source":"start","target":"prepare"},{"source":"prepare","target":"review"},{"source":"review","target":"record"},{"source":"record","target":"finish"}]}
```

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

Implemented DTOs: Detail is `{workflow,definition,currentPublishedVersion}`. Workflow summary is `{id,name,description,status,revision,currentPublishedVersionId,updatedAt}`. Snapshot is `{version,definition}`. Version summary is `{id,workflowId,versionNumber,sourceRevision,schemaVersion,canonicalizationVersion,name,description,definitionHash,publishedBy,publishedAt}`. Collections contain summaries only, never full graphs. Required create/save description may be an empty string. Unknown DTO fields, non-string metadata and non-integer expected revisions are 400; graph semantic errors may remain in bounded drafts. Invalid publication returns safe error code INVALID_WORKFLOW with `details:{valid:false,errors:[...]}`. GET detail/exact version returns historical metadata alongside the appropriate graph, never persistence entities. HTTP body filtering after authentication bounds direct/chunked bodies to 256 KiB and parser depth 32/string length 4096/number length 20 with duplicate-key rejection. Draft bounds additionally enforce depth 16, 4096 JSON values, 2000-character strings, 128 KiB, 50 nodes and 100 edges.

## Browser behavior and extending definitions

The editor uses the existing authenticated session abstraction, memory-only Query state and design tokens. Metadata and schema-derived node forms expose explicit Next node connections. Add inserts before the existing end when there is one unambiguous incoming connection; remove reconnects a single predecessor/successor and never chooses an arbitrary branch. Invalid bounded drafts remain saveable and correctable. Unsupported structures require explicit replacement; unknown fields/configurations are not silently discarded. Numeric/string/object config mismatches remain safe UI input states. No user expression, script or object conversion is evaluated.

Actual passing-journey captures: [desktop](../screenshots/phase2-workflow-desktop.png), [dark](../screenshots/phase2-workflow-dark.png) and [mobile](../screenshots/phase2-workflow-mobile.png). They contain synthetic disposable API-backed data, show two frozen versions and no execution result. Rendered desktop/mobile/theme layouts were reviewed; the mobile browser asserts no document-width overflow and 44px buttons. This does not establish WCAG conformance.

Save advances the revision. Validation/publication address only the persisted revision; unsaved changes must be saved first. A conflict preserves local edits. Reload asks before discarding dirty state, and background query updates never replace the editor. Unsaved edits remain in the current page: save before leaving or browser reload. Publication confirms the exact revision, shows its server result and refreshes history. Exact snapshots are read-only. Archive preserves historical reads; duplication copies the current persisted draft into an independent editable workflow with no inherited versions. Mutation visibility is a current capability hint; every server call reauthorizes.

Adding a definition type requires a versioned backend config schema and control-connection rules in GraphDefinition, frontend registry fields/defaults, validator/HTTP/UI/browser tests and documentation. Preserve the meaning of existing type versions and canonicalization version 1; incompatible semantics require a new type/schema version. A definition entry never creates a runtime capability: execution adapters and approval policies belong to their later phases. The initial types carry only next-control connections, with no data-port mappings or external operation references.

## Verification and progress

| Published increment | Observed hosted result |
| --- | --- |
| 4875c64 documentation/accepted design | [Passed](https://github.com/ChouaibSeishin/BridgeOps/actions/runs/38943543050) |
| 11555f5 registry/validation/canonical hash | [Passed](https://github.com/ChouaibSeishin/BridgeOps/actions/runs/38944732833) |
| 891cf82 V9/persistence/atomic publication | [Passed](https://github.com/ChouaibSeishin/BridgeOps/actions/runs/38946336182) |
| 77411d1 HTTP/security contracts | [Passed](https://github.com/ChouaibSeishin/BridgeOps/actions/runs/38947425562) |
| ee7c98b browser editor/history | [Passed](https://github.com/ChouaibSeishin/BridgeOps/actions/runs/38949564604) |

Focused local verification passed 28 graph cases, one module-import boundary case, ten lifecycle and two migration cases, the initial ten HTTP cases and a subsequent exact-input case. The HTTP boundary now rejects fractional/string/null/negative/overflow expected revisions and non-string metadata before coercion. Final frontend gates passed 37 tests, strict typecheck, lint and production build; production dependency audit found 0 vulnerabilities. These focused counts are historical checkpoints; final full Maven verify passed 162 tests (50 unit/MVC + 112 required PostgreSQL/browser), zero failures/errors/skips, with all XML reports checked.

The extended real Chromium journey passed create/configure/save/reload, persisted validation, invalid publication, correction, two immutable versions and exact historical inspection, stale conflict/local-edit preservation, tenant/read-only denial, independent duplication and archive/history, followed by all existing session/onboarding/recovery checks. Database assertions require exactly two workflow versions/audits from source revisions 2 and 4, an archived source, independent copy, no foreign-tenant workflow and no execution/approval/work tables. Captures use disposable synthetic data only. Browser locator corrections use accessible roles for nested selects/textareas; screenshot scroll positions are normalized. No assertion was skipped. All tests use isolated Testcontainers/operator fixtures and a test-only protected mail sink, never the local account or real SMTP.

Full Maven verification and additive Compose config/build/start passed, all three services healthy, V1–V9 installed. Aggregate account and installation fingerprints stayed identical; the existing user, consumed marker and environment/keys/volume were preserved. Actual users 1, organizations/workflows/versions 0. Public API/auth/CSRF and anonymous actual-nginx browser smoke passed, and production jar/assets exclude checked test fixtures. Final hosted publication remains pending. Initial actual local state: V8, one user, zero organizations, consumed bootstrap marker. Preserve environment, keys, account and volume; never bootstrap or reset them to verify workflows. Exact final evidence is in [project status](../PROJECT_STATUS.md). Published definitions do not execute until Phase 3.

## Commands

Use pinned Java 21 and Node 24.22.0 with Docker available. Build frontend assets/install Playwright Chromium before backend browser verification, following [README](../../README.md). Focused checks from repository root:

```powershell
./backend/mvnw.cmd -f backend/pom.xml -B -ntp '-Dtest=GraphDefinitionTest,WorkflowBoundariesTest' test
./backend/mvnw.cmd -f backend/pom.xml -B -ntp '-Dit.test=WorkflowLifecycleIT,WorkflowMigrationIT,WorkflowHttpIT' test-compile failsafe:integration-test failsafe:verify
./backend/mvnw.cmd -f backend/pom.xml -B -ntp '-Dit.test=BrowserJourneyIT' test-compile failsafe:integration-test failsafe:verify
npm.cmd --prefix frontend run typecheck
npm.cmd --prefix frontend run lint
npm.cmd --prefix frontend test
npm.cmd --prefix frontend run build
./backend/mvnw.cmd -f backend/pom.xml -B -ntp verify
node scripts/verify-docs.mjs
```

Then run the existing Compose config/build/start and public same-origin smoke gates without deleting volumes. Inspect actual hosted Actions after normal pushes; missing Docker/Chromium fails rather than skipping. Production deployment, WCAG conformance, external SMTP connectivity, throughput/HA and a completed security audit remain unverified.
