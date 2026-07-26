---
name: aidd-backport-spec
description: Recover an evidence-backed as-built specification and candidate natural-language requirements from existing Kotlin or TypeScript code, tests, and contracts. Use when Codex needs to document legacy behavior, compare implementation with an accepted model, build a code-to-requirement knowledge graph, or identify unsupported and inferred behavior without executing the target application.
---

# AIDD Backport Spec

Extract compiler- and contract-backed facts deterministically, then keep LLM interpretation in a separate candidate artifact. Acceptance never depends on model confidence.

## Required references

- Read [references/evidence-and-model.md](references/evidence-and-model.md) before classifying extracted or inferred claims.
- Read [references/cli.md](references/cli.md) before invoking the harness or interpreting a result.

## Workflow

1. Establish the extraction boundary.
   - Identify the repository, language (`kotlin` or `typescript`), relevant tests, OpenAPI or JSON Schema contracts, and output `spec-id`.
   - Do not execute application code or tests.
   - Do not infer a resolved Kotlin project model: v1 does not evaluate the target Gradle build. If source-only analysis is insufficient, report `UNSUPPORTED`.

2. Extract deterministic facts.

   ```bash
   python3 scripts/run_aidd_backport.py extract --repo <repo> --language <kotlin|typescript> --out .aidd/specs/<spec-id>/code-facts.json
   ```

   - Use compiler-derived symbols, types, nullability, states, guards, branches, exceptions, state updates, calls, documentation tags, and statically visible test assertions.
   - Include OpenAPI and JSON Schema facts when present.
   - Preserve file spans and SHA-256 evidence.
   - Recognize `@aidd.requirement <id>` and `@aidd.verifies <id>` as explicit links, not proof that the linked requirement is satisfied.
   - Mark unresolved dynamic calls, reflection, FFI, external I/O, and unresolved types `UNSUPPORTED` or candidate. Never infer safety from their absence in the extracted graph.

3. Build and validate the as-built model.
   - Map directly extracted facts to accepted `observed` claims only through the harness's deterministic auto-acceptance rule.
   - Keep inferred business intent, purpose, causality, implicit exceptions, and generalized rules `candidate`.
   - Validate evidence links before checking the model.
   - Run `check` with explicit reviewed bounds when the result must be more than provisional.

   ```bash
   python3 scripts/run_aidd_backport.py check --model .aidd/specs/<spec-id>/model.jsonld --out .aidd/specs/<spec-id>
   python3 scripts/run_aidd_backport.py render --facts .aidd/specs/<spec-id>/code-facts.json --out .aidd/specs/<spec-id>/as-built.md
   ```

4. Produce separate views.
   - Use the harness to render deterministic `as-built.md` from accepted facts.
   - Draft readable interpretations only in `candidate-prose.md`; label each statement with supporting fact IDs and retain `candidate` status.
   - Never copy candidate prose into `as-built.md`.

5. Compare with intended behavior when an accepted model exists.

   ```bash
   python3 scripts/run_aidd_backport.py diff --model .aidd/specs/<spec-id>/model.jsonld --against <accepted-model.jsonld> --out .aidd/specs/<spec-id>/diff.json
   ```

   Report matched behavior, missing implementation, extra implementation, contradiction, and insufficient evidence separately. Do not call extra implementation a defect without a human decision.

6. Deliver the result.
   - Report artifact paths, analyzed languages and boundaries, evidence coverage, unsupported constructs, verification status and bounds, diff categories, and human decisions still required.
   - Present deterministic facts before candidate interpretations.

## Guardrails

- Never use LLM confidence or prose quality as an acceptance signal.
- Never promote README or free-form comments above compiler, contract, and test evidence.
- Never execute the target application, tests, Gradle, or arbitrary package scripts. The v1 CLI records `--allow-build-tool` for compatibility but does not evaluate the target Gradle build.
- Never hide unsupported constructs or silently discard extraction failures.
- Never claim recovered behavior is intended behavior until a human approves that interpretation.
- Never describe bounded Alloy checking as an unqualified proof.
