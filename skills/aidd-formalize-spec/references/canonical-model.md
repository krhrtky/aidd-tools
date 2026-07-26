# Canonical semantic model

Use `model.jsonld` as the sole semantic model. Use the fixed local JSON-LD context shipped by the repository; do not resolve network contexts.

## Vocabulary

Node types:

`Requirement`, `Term`, `Entity`, `Type`, `State`, `Transition`, `Operation`, `Constraint`, `Invariant`, `Precondition`, `Postcondition`, `Error`, `Assumption`, `Example`, `Counterexample`, `CodeSymbol`, `TestCase`, `Contract`, `Evidence`, `HumanDecision`.

Edges:

`defines`, `constrains`, `derivesFrom`, `dependsOn`, `transitionsFrom`, `transitionsTo`, `implementedBy`, `testedBy`, `evidencedBy`, `contradicts`, `supersedes`.

Use graph edges for references. Every referenced ID must exist. IDs must be stable and unique within the model.

## Claim metadata

Every semantic claim must include:

- `status`: `candidate`, `accepted`, or `rejected`;
- `basis`: `stated`, `observed`, `derived`, or `assumed`;
- evidence with source path, line and column span, and SHA-256;
- `generatedBy` when known: `llm`, `human`, `harness`, or `extractor`;
- an accepted `HumanDecision` linked with `defines` or `constrains` when an LLM-generated or assumed claim is accepted.

Natural-language interpretation starts with `status: candidate`. Only explicit human approval can accept meaning extracted from prose. An accepted `HumanDecision` must use `generatedBy: human` and carry inline evidence for the decision record. Accepted `assumed` claims require that visible decision. Record tool versions in `manifest.json`.

## Constraint representation

Use the repository's typed JSON expression AST. Supported `op` values are `literal`, `ref`, `variable`, `current`, `not`, `and`, `or`, `implies`, `eq`, `neq`, `lt`, `lte`, `gt`, `gte`, `in`, `all`, `some`, `no`, `one`, `always`, `eventually`, `next`, and `until`. Follow the checked-in schema exactly. Never put raw Alloy expressions in a claim.

Use `{"op":"current"}` for the current state in a generated transition trace. Use `variable` only inside its matching `all` binder. The harness rejects unbound variables and Boolean, integer, string, or set type mismatches.

## Verification statuses

- `NO_COUNTEREXAMPLE_WITHIN_SCOPE`: no counterexample was found in the stated finite bounds.
- `COUNTEREXAMPLE`: the checker produced a violating instance or trace.
- `NO_INSTANCE_WITHIN_SCOPE`: constraints have no satisfying instance in the stated finite bounds.
- `PROVISIONAL`: only default or unapproved bounds were used.
- `TIMEOUT`: checking did not finish; this is not success.
- `UNSUPPORTED`: the model uses behavior the harness cannot analyze.
- `HUMAN_REVIEW_REQUIRED`: deterministic processing stopped for a semantic decision.

Default search bounds are 3 elements, 4-bit integers, and 10 states. Any result using them remains `PROVISIONAL`. Record bounds, assumptions, tool versions, inputs, outputs, hashes, and invocation arguments in the manifest.

Approved bounds require non-empty `approvedBy` and a `decisionId` beginning with `urn:aidd:`. A satisfiable `run` without an `Invariant` check remains `PROVISIONAL`.
