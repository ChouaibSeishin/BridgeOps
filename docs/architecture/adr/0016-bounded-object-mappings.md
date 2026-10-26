# ADR 0016: Bounded whole-object mappings

Status: Accepted for the Phase 5 schema-2 authoring increment, 2026-10-26.

## Context

Registered REST arguments contain typed `fields` and `pathParameters` objects. Existing schema-2 transforms construct bounded named objects, but the original mapping grammar could select only a named output field. Dynamic REST bodies therefore needed an explicit way to select a constructed object without expressions, nested scripts or workflow-controlled destinations.

## Decision

Extend schema-2 `{ "from": "nodeId", "path": "..." }` with the exact path `$` for the entire output of a dominating `manualTrigger`, `webhookTrigger` or `transform` only. `$` is a fixed selector, not JSONPath. Suffixes, indexes, wildcards and evaluation are unsupported. Other node outputs retain their existing registered named-field selectors. Dominance, self/future reference denial, output-size bounds, deep-copy behavior, operation argument validation and central approval remain mandatory. Object values cannot be interpolated into text parts.

The typed editor can build named transform fields from literals or upstream outputs, then map that complete object into an operation's declared object input. The registered connection independently validates its exact field names/types and revision before preparing approval or dispatch. Mapping supplies data only; it grants no authority and cannot retrieve credentials.

## Compatibility and consequences

This explicitly adds a previously invalid selector to schema 2; it changes no existing valid selector and introduces no node type, persisted name field or migration. Schema 1 remains unchanged. Canonicalization versions and historical bytes/hashes stay unchanged, with a golden hash test for the preceding schema-2 fixture. Existing executions retain their immutable snapshot. Arbitrary JSONPath, expressions and automatic field coercion remain unsupported.
