# Phase 5.5 — UX/UI foundation and usability

Status: in progress, 2026-10-29 UTC. This is a frontend milestone before Phase 6, preserving backend behavior, API/security contracts, workflow bytes/hashes and existing local data. No migration, environment/key change or production-data fixture is authorized by visual work.

## Preflight and baseline

Clean local master and fetched origin/master matched `4ff2e7a3d257d9addafaedc3ca43f23aafe1aa6a`; its exact hosted CI40474013684 succeeded. The locked stack is React 19.3.1, TypeScript 5.9.3, Tailwind 4.3.3, React Router 7.18.4, TanStack Query 5.104.3, Lucide 1.57.0 and React Flow 12.12.0. No new production dependency is planned. All 148 existing frontend tests, typecheck, lint and production build passed before edits. The fresh unchanged-asset baseline also passed all 135 unit/MVC tests and six retained disposable PostgreSQL/browser journeys, zero failures/errors/skips.

Read-only private preservation fingerprints cover all 66 existing database tables, environment/signing/CSRF/integration key material and named-volume identity. Existing services are healthy. The real installation is V24: the README's leftover development-checkpoint sentence saying V11 is stale and will be corrected, not treated as a reason to migrate/reset anything. The design foundation's proposed draft TEST action also conflicts with the delivered published-version contract; retain the existing explicit run/real-effects acknowledgement and introduce no new execution mode.

## UI audit

Source inspection covers the route tree, authenticated client, access queries, global/canvas CSS, controls and existing unit/browser assertions. The existing Phase 5 screenshots were inspected as rendered evidence; their application trees match this starting revision. Fresh browser captures are a separate baseline gate. The persisted support view and workflow canvas show the following concrete issues:

| Problem | Consequence | Files / intended change |
| --- | --- | --- |
| Four global sidebar destinations; organization tools only in page links; constant Operations / Workspace breadcrumb | Tenant context and the path between operations/governance screens are hard to follow | App.tsx and a shared WorkspaceShell, reusing live access and organization queries |
| Wrapping horizontal mobile navigation; no drawer | Navigation takes substantial vertical space and does not scale to current routes | Responsive shell with a native dialog drawer, focus return, keyboard/Escape behavior and actual authorized links |
| 8–11px secondary/action styles and inconsistent border/panel density | Important actions and technical metadata compete without a clear hierarchy | Existing tokens.css/app.css; semantic control/surface/status/canvas tokens, consistent typography and spacing |
| Long stacked workflow page; palette, graph and inspector arranged vertically; advanced JSON always expanded | Comparing a node with its settings requires scrolling; ordinary edits look technical | WorkflowCanvas.tsx/canvas.css and metadata layout: categorized palette, dark graph and light inspector with responsive stacking |
| Invalid-node anchors and generated IDs dominate validation | Finding a problem in the visual editor is difficult | Friendly validation links and explicit selected-node navigation, preserving stable IDs and server errors |
| Execution/approval records and connection/automation status use undifferentiated paragraphs | Current state, consequences and next action are hard to scan | Shared status/header/notice/detail patterns across executions, approvals and integrations |
| Support tickets and catalog entries are unstructured long blocks; repeated nested panels | Dense real data lacks row/section hierarchy | Compact tables or structured rows, bounded scroll regions, deliberate empty/filter states and explicit provenance |
| Confirmation panels declare dialogs without complete focus handling | Keyboard users can lose context or navigate behind a decision | Reusable native confirmation dialog retaining exact confirmations, field values and existing callbacks |

## Route and permission inventory

Global authenticated routes remain Organizations `/`, workflow organization chooser `/workflows`, Runtime `/runtime`, account/sessions `/account`, invitations and recovery. Organization overview/settings `/organizations/:id` retains membership, role and invitation capabilities. Organization workflow list/draft/published inspection uses workflow.read/edit/publish; execution list/detail uses execution.read with existing run/cancel/retry checks; approvals/inbox/detail/policy uses approval.read/decide and policy.manage. Connections and operation metadata use integration.read; configuration uses integration.manage. Support operations uses capability.read, with separate confirmed setup intersections. Automations/webhooks uses integration.read and the current management/run/write intersections. No AI, analytics, audit-query or settings destination is invented.

Inspect loading, empty, error, unavailable, permission-denied and stale states for all these routes. Keep source version/hash, exact approved arguments, attempts, receipts, deadlines, partial outcomes and one-time secret handling accessible. Theme switching is currently DOM/session-local; no credential or new preference storage is introduced to bypass the retained empty-storage browser assertions.

## Implementation sequence

1. Evolve semantic light/dark/canvas tokens and shared page, badge, field, notice and confirmation patterns. Check real rendered contrast/focus before broad rollout.
2. Replace only presentation shell markup with contextual, permission-aware navigation and responsive focus-managed drawer. Keep session/client/cache invalidation and every URL/redirect unchanged.
3. Recompose the React Flow editor into palette / graphite canvas / inspector; preserve its model, IDs, serialization, connection alternatives, typed properties and Apply/Discard guards. Make metadata and lifecycle actions compact and validation actionable.
4. Apply shared hierarchy to workflow/version lists, execution state/timeline/results, exact approvals, connection management, support data, automation/delivery history, organization/account/recovery screens. Consequential actions keep their existing confirmations and payloads.
5. Add focused frontend and real-browser coverage at 1440/1280/768/390px, including keyboard focus, long labels, errors, permission gates, dark canvas/light shell and retained save/validate/publish/run/approval flows. Run the full applicable frontend and backend/PostgreSQL/browser regression, strict reports, container/same-origin, dependency/secret and documentation gates.
6. Inspect genuine screenshots, compare preserved material/data privately, publish normal Conventional Commits/pushes and verify exact final hosted CI. No WCAG certification or completed redesign is claimed before those gates.

## Verification record

Baseline frontend: 21 files / 148 tests passed, zero failures/skips; typecheck, lint and build passed with the pinned Node 24.22.0 runtime. Browser baseline and implementation results will be appended when observed. Existing tests/assertions/security controls and timeouts remain required.

Fresh unchanged-asset browser baseline passed all 135 unit/MVC tests and all six required real Chromium journeys with zero failures/errors/skips. Captures were retained privately before the redesigned build. The first shared foundation build passed all 155 frontend tests (23 files), typecheck, lint and production build. Seven added cases cover live permission-based navigation, scoped organization context, no empty-tenant fetch on global routes, named drawer/focus return, honest status/empty states and cancellation while busy. An initial new title query matched both breadcrumb and sidebar; scoping it to the navigation landmark corrected the test assumption without removing the long-name check.

The first real redesigned canvas/triage journey passed, including rendered node/secondary-text contrast of at least 4.5:1 and all retained actual business/approval/receipt/history assertions. The expanded onboarding journey caught a mobile drawer keyboard-boundary failure. An explicit first/last focus-target loop supplements the native modal; strict TypeScript additionally required checking the actual first/last elements rather than only array length. The corrected focused tests/typecheck/lint/build passed, followed by all 135 units and the complete real onboarding/workflow/execution/recovery journey, zero failures/errors/skips. The new checks passed desktop widths 1440/1280, tablet 768 and mobile 390, including Tab/Shift+Tab containment, Escape, focus return, body contrast and global overflow. Desktop, mobile workspace/drawer and dark-canvas screenshots were inspected. No focus assertion or retained timeout was relaxed. Editor composition and operational page refinements remain subsequent increments; this is not the full phase release gate.

## Integrated presentation

The shared shell uses actual organization access and name queries, the existing URLs and session client, contextual breadcrumbs and permission-filtered Operations/Governance links. Global Organizations, Workflows, Runtime and Account remain available. There is no invented dashboard, analytics, audit-query or AI destination. Navigation becomes a focus-managed native dialog below the desktop breakpoint. Existing dark theme and reduced-motion behavior remain; theme is still session-local DOM state, with no new persistent browser storage.

`tokens.css` owns semantic surfaces, text, borders, selected/focus states, status colors and independent graphite canvas colors. `app.css` exposes the semantic palette through the existing Tailwind processor; `workspace.css` and `operations.css` apply shared layout and record patterns. PageHeader, StatusBadge, EmptyState, ConfirmationDialog and TableRegion provide reusable presentation. Status labels retain the actual persisted state, accompanied by icons. Dense tables have named keyboard-scrollable regions.

### Authoring and lifecycle

The React Flow workbench places a categorized palette beside the graph and inspector on desktop. Tablet places the inspector below; phone stacks the native grouped palette picker, canvas and inspector. Keyboard selectors for adding, inspecting and connecting nodes remain. Typed property forms and mapping controls retain every registered field, permission, revision and compatibility check. Advanced JSON is available in a collapsed section; unapplied or unsafe edits still block lifecycle mutations. Field errors are programmatically associated with the configuration form. Friendly saved-validation links select the affected node.

Compact and horizontal layouts, drag positions, zoom and selection are presentation state only. Resize fitting keeps the graph visible when its container changes size; Focus selected node centers the node and brings the graph into the page viewport. None of these values enter workflow JSON, published hashes or database records. Graph-v1 editing, graph-v2 conversion, historical exact JSON/large-integer handling and immutable published inspection retain their existing semantics.

Save, validate, publish, duplicate, archive and run remain separate actions. Publish, archive, reload/discard, graph conversion, start, cancel, linked retry, approval decisions, connection checks, example-data addition and automation rotation/revocation use the shared native confirmation pattern where they already require confirmation. No additional server mutation or external request is introduced by navigation, layout, validation links or screenshots.

### Operational routes

- Workflow and execution histories use compact real-data tables with state, revision/time and accessible secondary fingerprints. Publication metadata retains publisher, source revision, schema/canonicalization and exact hash.
- Execution detail prioritizes persisted state, timestamps, absolute deadline, duration and current step. Step outputs, logical operation IDs, attempts, retry schedules, approval references, partial outcomes and timeline remain available. Polling, stale warnings, cancellation and backend-confirmed retry eligibility are unchanged.
- Approval inbox/detail distinguish request state from execution state. Exact canonical proposed arguments remain directly visible before decisions; requester, policy, expiry and immutable source remain. Decision confirmation still explains independent worker revalidation and completed effects.
- Integrations show honest enabled/health/revision states and registered operations. Credential entry is visually separated; stored credentials are never returned or prefilled. Configured-destination and external-effects confirmation remains permission-bound.
- Support tickets use a contained table with filters, team assignment, revision and provenance. Responsibilities and persisted internal delivery history remain. No notes API or email delivery is invented; internal delivery still means persisted BridgeOps availability.
- Automation principals and webhook endpoints show persisted scope/status/revision, pinned versions and delivery links. Rotation/revocation confirmations retain expected revisions. One-time signing-key reveal/copy/dismiss stays transient and outside query/persistent caches.
- Organization tools use a compact permission-filtered row; organization IDs are secondary. Members, roles, invitations, account/session controls, onboarding, recovery and Runtime adopt the shared readable form, table, notice and responsive surface rules.

## Verification commands and evidence

Use the pinned Node runtime and repository Maven wrapper. Browser/integration fixtures are disposable PostgreSQL/Testcontainers instances, not the installed local account or volume.

```text
npm --prefix frontend run typecheck
npm --prefix frontend run lint
npm --prefix frontend test -- --maxWorkers=2
npm --prefix frontend run build
backend/mvnw.cmd -f backend/pom.xml -B -ntp verify
node scripts/verify-test-reports.mjs
node scripts/verify-docs.mjs
npm --prefix frontend audit --omit=dev --audit-level=high
gitleaks git --redact --no-banner
```

The composed editor/operational build passed all 160 frontend tests in 24 files, strict typecheck, lint and production build. Existing assertions were retained; formatting-specific queries now inspect the separate state badges and their containing records. Added coverage verifies categorized controls, presentation-only layout, pending-edit protection, collapsed advanced JSON, associated errors and friendly validation navigation. The production dependency audit reported zero vulnerabilities; history scanning reported no leaks.

Visual checks in `frontend/e2e/ux.mjs` run within existing real journeys. They capture 1440/1280/768/390px layouts, keyboard-scrollable tables, drawer focus containment/return, graph visibility after resizing and selected-node focusing, real status text contrast, control focus contrast/hit targets and global overflow. Captures are written under `backend/target/browser-checks/ux-*.png`; the existing journey captures remain. No mocked metrics, production credentials or one-time webhook secrets are used for visual evidence.

Visual review found a phone graph outside its resized viewport. The new real node-intersection assertion detected the gap in the earlier geometry-only checks. A subsequent selected-node assertion exposed page scrolling as a separate issue; focus now also brings the graph into view. These checks remain enabled. Final full-suite, container, preservation and exact hosted release results will be recorded after completion.

## Remaining scope boundaries

This is a UX foundation, not formal WCAG certification. Dedicated screen-reader and broader assistive-technology audits remain release-polish work. Current API/permission boundaries govern every displayed action. AI/MCP, new connectors, new schema/input semantics, arbitrary requests and database changes are outside this milestone. The current theme behavior is preserved rather than adding preference storage.
