# Phase 5.5 — UX/UI foundation and usability

Status: in progress, 2026-10-29 UTC. This is a frontend milestone before Phase 6, preserving backend behavior, API/security contracts, workflow bytes/hashes and existing local data. No migration, environment/key change or production-data fixture is authorized by visual work.

## Preflight and baseline

Clean local master and fetched origin/master match `4ff2e7a3d257d9addafaedc3ca43f23aafe1aa6a`; its exact hosted CI40474013684 succeeded. The locked stack is React 19.3.1, TypeScript 5.9.3, Tailwind 4.3.3, React Router 7.18.4, TanStack Query 5.104.3, Lucide 1.57.0 and React Flow 12.12.0. No new production dependency is planned. All 148 existing frontend tests, typecheck, lint and production build passed before edits. The six retained disposable PostgreSQL/browser journeys are being rerun against those unchanged built assets; their fresh results remain pending.

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
