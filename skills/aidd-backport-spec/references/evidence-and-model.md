# Backport evidence and semantic model

## Evidence priority

Classify sources without converting source priority into business intent:

1. Compiler/type-system facts and explicit executable contracts.
2. OpenAPI and JSON Schema declarations.
3. Statically visible test assertions and fixtures.
4. KDoc/JSDoc declarations and AIDD tags.
5. README and other prose as candidate context only.

Each fact must retain source path, line and column span, SHA-256, extractor name, and extractor version. Evidence hash mismatch, missing referenced facts, or unresolved symbols must fail validation or be reported as unsupported.

## Acceptance boundary

A harness rule may accept a claim only when a compiler or supported contract parser directly extracts it. Such claims use `status: accepted`, `basis: observed`, and `generatedBy: extractor`.

Keep these as candidates until human approval:

- business purpose or motivation;
- causality not encoded by the program;
- intended rather than observed behavior;
- implicit exceptions and generalized rules;
- interpretations from comments or README files;
- all LLM-produced prose and graph additions.

Never accept a claim from an LLM confidence score. Treat `@aidd.requirement` and `@aidd.verifies` as trace links rather than satisfaction proofs.

## Relevant model vocabulary

Use the canonical node and edge types shipped with the repository schema. Backport work normally creates `CodeSymbol`, `TestCase`, `Contract`, `Evidence`, `State`, `Transition`, `Operation`, `Error`, and constrained behavioral claims connected by `implementedBy`, `testedBy`, `evidencedBy`, and `derivesFrom`.

The `status` field is `candidate`, `accepted`, or `rejected`. The `basis` field is `stated`, `observed`, `derived`, or `assumed`.

## Unsupported boundary

Surface dynamic dispatch that cannot be resolved, reflection, runtime code generation, FFI, external I/O semantics, unresolved types, and build-model failures. Use `UNSUPPORTED` rather than assuming the omitted behavior is safe.

The v1 CLI does not evaluate the target Gradle build. `--allow-build-tool` is recorded for compatibility but does not expand analysis. Report `UNSUPPORTED` when source-only Kotlin analysis cannot resolve the required semantics.
