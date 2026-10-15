# Documentation conflict resolution — 2026-10-15

The user's documentation review authorizes conflict resolution and incremental Phase 1 implementation. Preserve the modular monolith and existing security/runtime boundaries. This document supersedes Phase 0's awaiting-review checkpoint; no further architecture approval is required for these agreed decisions.

| Issue | Resolution |
| --- | --- |
| A: Java/test compatibility | Java 21, Boot 4.1.1 and BOM-managed Jupiter 6.0.3. Explicitly amend the original JUnit 5 requirement rather than override the BOM or downgrade Boot. Stable artifacts verified against official release metadata. Maven 3.9.16; springdoc 3.1.1. See the technology lock. |
| B: Provisioning | A non-web, explicit bootstrap command creates ONE verified initial account with organization.create privilege, no organization yet. First login creates organization plus Owner membership transactionally. Subsequent users join via authorized invite. Bootstrap is disabled by default, requires explicit mode and secret input, holds a database installation lock and refuses once the singleton installation marker is consumed. It never resets/replaces an account and has no HTTP endpoint. Production requires an operator-controlled offline invocation; normal server startup cannot bootstrap. |
| C: Approval | Requester plus one different eligible user; exactly one approve/reject decision per request. No two-approver quorum. Backend enforces identity, permissions, policy, expiry and concurrency. |
| D: Deadline | Absolute deadline = execution.created_at + 24h by default. Includes queue, retries, delays and approval waits. Every dispatch/resume and periodic reconciliation checks deadline; expired work fails with DEADLINE_EXCEEDED and outstanding approval is expired. No state can reset the clock. |
| E: Test effects | Label "Run test — real effects", show destinations/actions and require explicit confirmation. API requires effectsAcknowledged=true before creating TEST execution. Test is not dry-run; policy/authorization still apply. Deterministic demo connectors remain synthetic. |
| F: Reporting | No private reporting channel exists. Remove the assumption of an existing private contact. Public release requires a configured, tested private reporting channel. Do not solicit sensitive reports through a nonexistent method. |
| G: Roadmap | Adopt the review's seven phases: 1 foundation/auth, 2 definitions, 3 execution, 4 approvals, 5 integrations/webhooks, 6 AI/MCP, 7 polish/delivery. Minimal synthetic capability in Phase 3 enables execution before full connectors. Basic forms precede visual canvas. |
| G: New organization authority | organization.create is an account-level grant given only to the initial bootstrap account or explicit operator onboarding. Membership does not automatically grant installation-wide onboarding. Creation atomically checks grant and creates Owner membership. |
| G: Invite roles and actors | Bootstrap grants no platform-admin privilege. Invitees get only checked tenant roles; no automatic organization.create grant. Automated actions use explicit automation principals, not fictitious human accounts. |
| G: Implementation claims | Phase 1A supplies only the runnable technical foundation. Login/provisioning/tenant APIs ship in Phase 1B/C. No fake login or seeded dashboard statistics represent those features early. |

## Exact documentation change set

README.md; SECURITY.md; docs/SECURITY.md (new pointer); docs/PHASE_0_REVIEW.md; docs/PROJECT_STATUS.md; docs/product/MASTER_PROJECT_PROMPT.md; docs/product/REQUIREMENTS.md; docs/product/BACKLOG.md; docs/architecture/SYSTEM.md; docs/architecture/DATABASE.md; docs/architecture/EXECUTION.md; docs/api/CONTRACT_OUTLINE.md; docs/security/THREAT_MODEL.md; docs/development/TECHNOLOGY.md; docs/development/PHASE_1_VERIFICATION.md; docs/development/REPOSITORY.md; docs/design/FOUNDATION.md; ADRs 0007, 0010 and 0011; ADR index; new ADR 0013 (stack) and 0014 (bootstrap); this report; docs/screenshots/foundation-desktop.jpg and foundation-mobile-dark.jpg (actual captures); docs/development/PHASE_1_IMPLEMENTATION.md; docs/development/DEPENDENCY_LOCK.md (generated version evidence).

Original product intent and twelve ADRs are retained. The master prompt is amended with controlling current decisions and a revised roadmap rather than discarded. Prior observed tool versions remain historical evidence; new verification is recorded separately.

## Phase 1A verification outcome

The foundation is now implemented and tested; the full authentication phase and business vertical slice are not complete. See PROJECT_STATUS.md for exact executed checks, initial failures/corrections, runtime limitations and next increments. Source files and commands are listed in the implementation sequence and README. The four accidentally damaged documents were restored before final validation; no character-replacement workaround remains in repository tooling.
