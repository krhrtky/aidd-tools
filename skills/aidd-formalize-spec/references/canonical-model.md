# Canonical semantic model

Use `model.jsonld` as the sole semantic model. Use the fixed local JSON-LD context shipped by the repository; do not resolve network contexts.

## Vocabulary

Schema 1.1 adds pure-function contracts while retaining 1.0 model compatibility.

Node types:

`Requirement`, `Term`, `Entity`, `Type`, `State`, `Transition`, `Operation`, `Parameter`, `Result`, `Constraint`, `Invariant`, `Precondition`, `Postcondition`, `Error`, `Assumption`, `Example`, `Counterexample`, `CodeSymbol`, `TestCase`, `Contract`, `Evidence`, `HumanDecision`.

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

For natural-language extraction, every semantic node produced by the LLM uses `generatedBy: llm`, even when its `basis` is `stated`. Give every such node the narrowest supporting source span and the source file SHA-256.

## Pure-function contracts

A schema 1.1 contract uses:

- `Parameter` and `Result` nodes with `valueType`;
- an `Operation` with ordered `accepts`, exactly one `returns`, and optional `mayFailWith` error IDs;
- a `Contract` with `total`, exactly one Operation in `defines`, and zero or more `Precondition`, one or more `Postcondition`, and zero or more `Error` IDs in `constrains`. Multiple preconditions and postconditions are conjunctions.

`valueType` shapes:

```json
{"kind":"int"}
{"kind":"bool"}
{"kind":"string"}
{"kind":"enum","typeId":"urn:aidd:example:type:status"}
{"kind":"set","elementType":{"kind":"int"}}
{"kind":"list","elementType":{"kind":"string"}}
```

Define an Enum with a `Type` node and a non-empty, duplicate-free `members` array. Nested collections are unsupported in v1.

`total: true` means every input has exactly one outcome: one result satisfying all postconditions when the preconditions hold, or exactly one declared error otherwise. Error conditions must therefore be mutually exclusive and cover the precondition's complement. `total: false` permits inputs outside the success and declared-error domains. In either case, a successful input must have exactly one result.

## Constraint representation

Use the repository's typed JSON expression AST. In addition to the 1.0 graph operators, schema 1.1 pure-function contracts support:

- values: `valueRef`, `enumLiteral`, `setLiteral`, `listLiteral`;
- integer operations: `add`, `sub`, `mul`;
- collection queries: `size`, `contains`, `index`;
- Set operations: `union`, `intersect`, `difference`;
- List operations: `append`, `concat`, `slice`;
- Boolean and comparison operators: `not`, `and`, `or`, `implies`, `eq`, `neq`, `lt`, `lte`, `gt`, `gte`.

`valueRef` identifies a Parameter or Result. `enumLiteral` carries `typeId` and `member`. Set/List literals carry their element `valueType` and `args`. Follow the checked-in schema exactly and never insert free-form Alloy.

String v1 supports equality, inequality, and collection membership only. Regex, string concatenation, and string length are unsupported. Division, modulo, nested collections, and higher-order collection operations are also unsupported and must fail closed rather than be approximated.

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

Candidate exploration always reports top-level `PROVISIONAL`, regardless of bound approval, because it contains unaccepted semantics. Its `boundedOutcome` preserves the exact finite result.

Approved bounds require non-empty `approvedBy` and a `decisionId` beginning with `urn:aidd:`. A satisfiable `run` without an `Invariant` check remains `PROVISIONAL`.
