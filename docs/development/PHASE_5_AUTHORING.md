# Phase 5 — Typed workflow authoring

Status: implemented and locally verified; clean full-suite and preserved-data release verification is in progress. This guide describes the Phase 5 development build, not the still-preserved local V11 installation. [Implementation and verification record](PHASE_5_INTEGRATIONS.md).

## Build a workflow

1. Open an organization’s Workflows page and create a draft. Existing schema-v1 drafts retain their form editor; **Convert draft to schema v2** requires explicit confirmation and changes only the editable draft. Published versions and hashes remain immutable.
2. Add supported nodes with the canvas palette. Use the canvas or the keyboard-accessible next-node and true/false connection selectors. Edges determine execution; card positions are presentation only. **Reset layout and fit** affects the view, not the saved definition.
3. Select a node through its friendly label. Trigger panels declare at most 32 flat STRING/INTEGER/BOOLEAN fields. Other panels configure record messages, approval prompts, bounded delays, typed conditions and named transform fields.
4. Capability, notification and configured-HTTP panels select actual organization connections and registered operations. A connection change captures its current revision. A previously saved revision is retained until **Use current connection revision** is explicitly chosen. Disabled, unavailable, stale or incompatible connections are reported; no credential or destination is retrieved by these property panels.
5. Configure each input as a literal, a guaranteed earlier output or at most 16 primitive text parts. Source suggestions use graph dominance, excluding self/future references and values available only on another branch. Labels are readable; stable IDs remain secondary technical details. REST output choices come from declared projected fields of the matching current connection revision.
6. Transforms construct at most 32 named fields. Select **All declared fields (object)** to map a whole transform or declared trigger object into an operation’s object input. This uses the exact `$` selector from [ADR 0016](../architecture/adr/0016-bounded-object-mappings.md), not JSONPath or an expression language. Whole objects cannot be text parts.
7. REST literal body fields and path parameters also have declared text/integer/boolean controls. Support-list priorities and statuses have bounded selection lists. Structured literal JSON and advanced property JSON remain available; neither permits code, arbitrary HTTP destinations, headers, SQL or secret resolution.
8. **Apply node properties** updates the local draft only. Unapplied or invalid property edits block save/publication until corrected or explicitly discarded. Invalid structured text remains available to correct. Unknown saved fields are retained; changing an operation replaces its declared argument field set only after an explicit edit. Server validation remains authoritative.
9. Save the draft, validate the persisted revision, then explicitly publish. Publication creates an immutable snapshot and performs no action. A separate run requires current execution authority, typed input and confirmation; external-capability runs additionally acknowledge provider effects. Editing inputs or start delay clears that acknowledgement.

## Example business flow

The opt-in triage installer creates example data and three dedicated internal connections only after confirmation. Its manual and webhook templates do not create executions, principals or endpoint keys. The same manual workflow can be authored through the canvas:

`Manual trigger → Support list → Team resolution → Condition`

The true branch is `Approval → Support assignment → Internal notification → End`; the false branch records a no-work transform and reaches End. Map the trigger limit into Support list, its tickets into Team resolution, resolution count into the condition, and exact planned assignments into the protected action. Compose notification text from literals and the committed assignment result. No external email is sent by this notification capability. Configured SMTP continues to serve invitation/recovery mail; it does not turn an internal record or notification into an email connector.

Authoring requires workflow.edit; publication requires workflow.publish. Connection management and execution authority remain separate backend capabilities. Protected writes wait for a different currently eligible approver. Automation approvals additionally exclude the principal creator and accepted endpoint revision author. Starting or publishing never approves an operation.

## Inspect and recover

Execution history/detail reads persisted status, pinned source version/hash, steps, attempts, deadlines, approval references and timeline. Technical step details expose the stable logical operation ID for reconciliation. Cancellation prevents future dispatch at durable checkpoints and does not undo completed or already-sent effects. Unknown external write outcomes require reconciliation rather than blind replay. REST reads retry only eligible failures within the original deadline and attempt limit; a provider idempotency header is not an exactly-once guarantee.

Browser integer editing is exact from -9007199254740991 to 9007199254740991. API-created definitions containing larger signed 64-bit integers are read-only in the browser: inspect the original definitionJson source instead of rounded diagram values. Draft saves and unsafe property application are blocked; the authenticated API retains signed 64-bit support. Published source and historical hashes remain unchanged.

## Metadata contract and limits

The existing authenticated operation-descriptor endpoint now includes sorted pathParameters names alongside classification, approval requirement and request/response field types. It exposes no provider route, destination, credential or secret reference and still requires live tenant integration.read. No new execution or raw-HTTP endpoint is added.

The engine remains a PostgreSQL-backed modular monolith. This phase implements registered internal capabilities, controlled REST operations and signed webhook-triggered runs. AI, MCP, arbitrary expressions/scripts, external email/SMS notifications and clock-based trigger scheduling remain outside this release. Draft TEST execution is not exposed.
