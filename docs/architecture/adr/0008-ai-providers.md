# ADR-0008-ai-providers: Provider-independent AI

Status: Proposed. Date: 2026-10-15.

## Context

The demonstration must work without paid credentials and models are untrusted planners.

## Alternatives

Tie domain services to one vendor SDK; let generated text execute directly.

## Decision

Narrow AiProvider port, validated structured responses, deterministic visibly labeled mock, one real adapter, authorized tool orchestration and ordinary execution/approval submission.

## Consequences

Mock cannot reproduce open-ended intelligence. Adapter contract tests and quotas are needed; conversation persistence is opt-in.
