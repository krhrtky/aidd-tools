---
name: aidd-formalize-spec
description: Convert natural-language requirements into a reviewable candidate JSON-LD semantic model, including pure-function contracts, and run deterministic validation and bounded Alloy exploration. Use when Codex needs to formalize prose requirements, expose ambiguity or contradictions before implementation, explain counterexamples, or prepare implementation contracts without generating application code.
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

2. Establish whether a pure-function contract is sufficiently specified.
   - Require a function name, ordered typed parameters, one typed result, success preconditions, postconditions, explicit error conditions, and whether the function is total or partial.
   - Require enough information to determine whether every valid input has exactly one result. For a total contract, require exactly one outcome—success or one error—for every input.
   - Ask the human concrete questions if any required meaning is ambiguous, conflicting, or missing. Do this before writing `model.jsonld`; do not guess, silently add an assumption, or emit a partial model.
   - Restrict v1 contracts to the types and operators in [references/canonical-model.md](references/canonical-model.md). Stop with `UNSUPPORTED` when the requirement needs behavior outside that subset.

3. Extract candidates from the natural language.
   - Identify terms, entities, states, transitions, operations, parameters, results, contracts, constraints, invariants, preconditions, postconditions, errors, assumptions, examples, and unresolved questions.
   - Represent relationships as graph edges, not duplicated prose.
   - Encode constraints with the typed expression AST. Do not insert free-form Alloy.
   - Mark every LLM-produced semantic claim with `status: candidate` and `generatedBy: llm`.
   - Use `basis: stated` only for source-explicit meaning, `derived` for an interpretation logically obtained from the source, and `assumed` for a human-authorized premise not stated by the source. Do not use `observed` for natural-language extraction.
   - Attach source path, exact line/column span, and the source file SHA-256 to every semantic claim. A broad file-level citation is not a substitute for the narrowest supporting span.

4. Surface semantic decisions.
   - List ambiguity, conflicting interpretations, missing behavior, hidden assumptions, and undefined boundary cases.
   - Offer concrete alternatives and examples or counterexamples.
   - Ask a human to approve or reject semantic claims. Do not approve them on the human's behalf.
   - Record a human decision only after explicit approval.

5. Run deterministic checks with `scripts/run_aidd_formalize.py`, in this order.

   ```bash
   python3 scripts/run_aidd_formalize.py validate --model .aidd/specs/<spec-id>/model.jsonld
   python3 scripts/run_aidd_formalize.py explore --model .aidd/specs/<spec-id>/model.jsonld --out .aidd/specs/<spec-id>
   ```

   Add `--bounds <bounds.json>` only when using an explicitly reviewed exploration scope. `explore` writes `candidate-spec.md` and machine artifacts. Do not use accepted-only `check` or `run` as a substitute for candidate exploration.

6. Interpret, repair, and rerun.
   - Treat validation errors as model defects.
   - Explain `COUNTEREXAMPLE` and `NO_INSTANCE_WITHIN_SCOPE` in the user's domain language, while preserving the exact machine result.
   - Treat `TIMEOUT`, `UNSUPPORTED`, and `HUMAN_REVIEW_REQUIRED` as non-success.
   - Candidate exploration is always `PROVISIONAL`, including with approved bounds. Use `boundedOutcome` to distinguish a counterexample, no instance, or no counterexample within the finite scope.
   - Propose model changes, but request semantic approval before changing a claim to `accepted`.

7. Deliver the result.
   - Report artifact paths, verification status, bounds, assumptions, unresolved decisions, and counterexamples.
   - Distinguish accepted claims from candidates.
   - State that v1 emits candidate pure-function contracts and bounded evidence, not accepted semantics or application code.

## Guardrails

- Never use LLM confidence, fluent prose, test passage, or absence of a counterexample as semantic approval.
- Never weaken a constraint, add an assumption, change bounds, or delete evidence merely to obtain a passing result.
- Never fetch a remote JSON-LD context. Use the repository's fixed local context.
- Keep IDs stable across revisions and preserve rejected claims for auditability when the model format supports them.
- Keep harness output machine-generated. Place explanatory prose outside deterministic artifacts unless the CLI explicitly owns that artifact.
