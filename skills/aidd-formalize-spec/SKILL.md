---
name: aidd-formalize-spec
description: Convert natural-language requirements into a reviewable candidate JSON-LD semantic model, knowledge graph, typed constraints, and deterministic Alloy verification artifacts. Use when Codex needs to formalize prose requirements, expose ambiguity or contradictions before implementation, explain counterexamples, or prepare implementation contracts without generating application code.
---

# AIDD Formalize Spec

Turn prose into a candidate model; let the deterministic harness judge only structural and bounded formal properties. Never treat an LLM interpretation as accepted semantics.

## Required references

- Read [references/canonical-model.md](references/canonical-model.md) before creating or changing `model.jsonld`.
- Read [references/cli.md](references/cli.md) before invoking the harness or interpreting a result.

## Workflow

1. Establish inputs and output location.
   - Identify the source requirement files and assign a stable `spec-id`.
   - Preserve source paths and calculate SHA-256 values for evidence.
   - Use `.aidd/specs/<spec-id>/` unless the user specifies another location.

2. Extract candidates from the natural language.
   - Identify terms, entities, states, transitions, operations, constraints, invariants, preconditions, postconditions, errors, assumptions, examples, and unresolved questions.
   - Represent relationships as graph edges, not duplicated prose.
   - Encode constraints with the typed expression AST. Do not insert free-form Alloy.
   - Mark every LLM-produced semantic claim `candidate`.
   - Mark provenance as `stated` only when the source explicitly says it; otherwise use `derived` or `assumed`.
   - Attach source evidence and generator metadata to every claim.

3. Surface semantic decisions.
   - List ambiguity, conflicting interpretations, missing behavior, hidden assumptions, and undefined boundary cases.
   - Offer concrete alternatives and examples or counterexamples.
   - Ask a human to approve or reject semantic claims. Do not approve them on the human's behalf.
   - Record a human decision only after explicit approval.

4. Run deterministic checks with `scripts/run_aidd_formalize.py`.

   ```bash
   python3 scripts/run_aidd_formalize.py validate --model .aidd/specs/<spec-id>/model.jsonld
   python3 scripts/run_aidd_formalize.py check --model .aidd/specs/<spec-id>/model.jsonld --out .aidd/specs/<spec-id>
   python3 scripts/run_aidd_formalize.py render --model .aidd/specs/<spec-id>/model.jsonld --out .aidd/specs/<spec-id>/spec.md
   ```

   Prefer `run` when the candidate model already exists and the CLI can perform the whole pipeline. Supply an explicitly reviewed bounds file when a bounded result is intended to be accepted.

5. Interpret, repair, and rerun.
   - Treat validation errors as model defects.
   - Explain `COUNTEREXAMPLE` and `NO_INSTANCE_WITHIN_SCOPE` in the user's domain language, while preserving the exact machine result.
   - Treat `TIMEOUT`, `UNSUPPORTED`, and `HUMAN_REVIEW_REQUIRED` as non-success.
   - Treat default-bound results as `PROVISIONAL`; never restate bounded checking as an unqualified proof.
   - Propose model changes, but request semantic approval before changing a claim to `accepted`.

6. Deliver the result.
   - Report artifact paths, verification status, bounds, assumptions, unresolved decisions, and counterexamples.
   - Distinguish accepted claims from candidates.
   - State that v1 emits specification and implementation contracts, not application code.

## Guardrails

- Never use LLM confidence, fluent prose, test passage, or absence of a counterexample as semantic approval.
- Never weaken a constraint, add an assumption, change bounds, or delete evidence merely to obtain a passing result.
- Never fetch a remote JSON-LD context. Use the repository's fixed local context.
- Keep IDs stable across revisions and preserve rejected claims for auditability when the model format supports them.
- Keep harness output machine-generated. Place explanatory prose outside deterministic artifacts unless the CLI explicitly owns that artifact.

