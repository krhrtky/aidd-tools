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

3. Stop before writing ambiguous business meaning.
   - Inventory the candidate vocabulary, rules, states, operations, success conditions, explicit errors, and scenarios supported by fact IDs.
   - If a business term has multiple plausible meanings, rules conflict, or an operation lacks a success or error condition, return `AMBIGUOUS_BUSINESS_MEANING` with concrete alternatives and the supporting fact IDs.
   - Do not write or partially retain `model.jsonld` or `candidate-prose.md` until the user resolves every blocking ambiguity.
   - Record an answer as a candidate `HumanDecision` linked to the affected claims. An answer does not automatically accept those claims.

4. Add candidate business meaning to the canonical model.
   - Map directly extracted facts to accepted `observed` claims only through the harness's deterministic auto-acceptance rule.
   - Create the smallest evidence-supported set of `Term`, `Entity`, `State`, `Transition`, `Requirement`, `Operation`, `Error`, and `Example` nodes. Add a schema 1.1 `Contract` with typed `Parameter`, one `Result`, preconditions, postconditions, explicit errors, and `total` only when the facts support the full contract.
   - Mark every Skill-created semantic claim `status: candidate`, `generatedBy: llm`, and `basis: stated|derived|assumed`. Never use `observed` for LLM business meaning.
   - Give every Skill-created claim at least one `evidencedBy` edge to an existing CodeFact ID. Preserve inline path/span/SHA-256 evidence when prose is the source.
   - Keep conceptual nodes (`Term`, `Entity`, `State`), specification nodes (`Operation`, constraints, errors), and implementation nodes (`CodeSymbol`, `TestCase`, `Evidence`) separate. Connect them only with `implementedBy`, `testedBy`, and `evidencedBy`.
   - Declare the model purpose as reviewing recovered business meaning, not proving intended correctness. Do not place implementation class names, repository types, or IDs in business labels.

   ```bash
   python3 scripts/run_aidd_backport.py validate \
     --facts .aidd/specs/<spec-id>/code-facts.json \
     --model .aidd/specs/<spec-id>/model.jsonld \
     --repo <repo>
   ```

5. Validate, explore, and render separate views.
   - Run accepted-only `check` when validating observed semantics. Candidate nodes never participate in this result.
   - Run `explore` for candidate meaning. It reuses the formalization harness, treats accepted nodes as premises, excludes rejected nodes, and always writes top-level `PROVISIONAL`.
   - A counterexample is a successful exploration finding, not CLI failure. Read `boundedOutcome` and diagnostics from `exploration.json`.
   - Use the harness to render deterministic `as-built.md` from accepted facts.
   - Render `candidate-prose.md` from the candidate graph. It contains the headings 概念, 仕様, 実装根拠 and the sections 用語, 業務ルール, 状態, 操作, 例外, シナリオ, 未確定事項.
   - Never copy candidate prose into `as-built.md`.

   ```bash
   python3 scripts/run_aidd_backport.py check \
     --model .aidd/specs/<spec-id>/model.jsonld \
     --out .aidd/specs/<spec-id>
   python3 scripts/run_aidd_backport.py explore \
     --model .aidd/specs/<spec-id>/model.jsonld \
     --facts .aidd/specs/<spec-id>/code-facts.json \
     --out .aidd/specs/<spec-id>
   python3 scripts/run_aidd_backport.py render \
     --facts .aidd/specs/<spec-id>/code-facts.json \
     --out .aidd/specs/<spec-id>/as-built.md
   python3 scripts/run_aidd_backport.py render \
     --model .aidd/specs/<spec-id>/model.jsonld \
     --view candidate-business \
     --out .aidd/specs/<spec-id>/candidate-prose.md
   ```

6. Compare with intended behavior when an accepted model exists.

   ```bash
   python3 scripts/run_aidd_backport.py diff --model .aidd/specs/<spec-id>/model.jsonld --against <accepted-model.jsonld> --out .aidd/specs/<spec-id>/diff.json
   ```

   Report matched behavior, missing implementation, extra implementation, contradiction, and insufficient evidence separately. Do not call extra implementation a defect without a human decision.

7. Deliver the result.
   - Report artifact paths, analyzed languages and boundaries, evidence coverage, unsupported constructs, verification status and bounds, diff categories, and human decisions still required.
   - Present deterministic facts before candidate interpretations.

## Guardrails

- Never use LLM confidence or prose quality as an acceptance signal.
- Never promote README or free-form comments above compiler, contract, and test evidence.
- Never execute the target application, tests, Gradle, or arbitrary package scripts. The v1 CLI records `--allow-build-tool` for compatibility but does not evaluate the target Gradle build.
- Never hide unsupported constructs or silently discard extraction failures.
- Never claim recovered behavior is intended behavior until a human approves that interpretation.
- Never describe bounded Alloy checking as an unqualified proof.
