# `aidd-backport` CLI

Invoke through `../scripts/run_aidd_backport.py`; it resolves `AIDD_TOOLS_HOME/bin/aidd-backport`, then `PATH`, then the Git root's `bin/aidd-backport`.

## Commands

```text
extract --repo <path> --language <kotlin|typescript> --out <code-facts.json> [--contracts ...] [--allow-build-tool]
validate --facts <json> --model <jsonld> [--repo <path>]
check --model <jsonld> [--bounds <json>] --out <directory>
explore --model <jsonld> [--bounds <json>] [--facts <json>] --out <directory>
render --facts <json> --out <markdown>
render --model <jsonld> --view <accepted|candidate-business> --out <markdown>
diff --model <observed.jsonld> --against <accepted.jsonld> --out <diff.json>
run --repo <path> --language <kotlin|typescript> --out <directory> [--model <jsonld>] [--bounds <json>] [--contracts ...]
```

`extract` creates compiler- and contract-backed `code-facts.json`. `validate` checks fact-to-evidence and model links; pass `--repo` to recompute source hashes against the live repository. For LLM candidates it also requires `candidate`, a non-`observed` basis, and at least one `evidencedBy` reference to an existing CodeFact.

`check` preserves accepted-only formal checks. `explore` reuses the formalization harness with accepted premises and candidate targets, excludes rejected claims, and writes a top-level `PROVISIONAL` result with the finite finding in `boundedOutcome`. Pass the already validated `code-facts.json` through optional `--facts` to bind its hash and extractor identity into the manifest; this option does not change exploration meaning. A counterexample is an exploration result, so the backport `explore` command exits `0` after writing it. Invalid input exits `2`; an unsupported candidate exploration exits `3`.

`render --facts` preserves deterministic `as-built.md`. `render --model --view candidate-business` deterministically creates domain knowledge from candidate nodes while displaying basis, generator, status, and evidence references. `diff` compares the recovered model with accepted intent. `run` performs the deterministic extractor/accepted pipeline and never generates LLM meaning.

The v1 Kotlin extractor uses compiler PSI for direct syntax facts. It does not provide classpath-backed K2 Analysis API resolution or evaluate the target Gradle build; inferred public types are reported as `UNSUPPORTED / SEMANTIC_CLASSPATH_REQUIRED`. `--allow-build-tool` records permission but does not execute Gradle. The TypeScript extractor uses its built `dist` entrypoint.

OpenAPI and JSON Schema ingestion is available through the TypeScript extraction path in v1. Combining `--language kotlin` with `--contracts` returns unsupported instead of silently dropping the contracts.

Expected output directory:

```text
.aidd/specs/<spec-id>/
  model.jsonld
  model.als
  accepted.als
  candidate.als
  bounds.json
  verification.json
  exploration.json
  manifest.json
  counterexamples/
  code-facts.json
  as-built.md
  candidate-prose.md
```

Keep `as-built.md` deterministic. LLM-written interpretation belongs only in `candidate-prose.md` and remains pending human approval.

The manifest records tool and Alloy versions, command arguments, input and output hashes, bounds and approval state, top-level status, `boundedOutcome`, and machine-readable diagnostics. Existing `model.als`, `verification.json`, and formalization exit codes remain available for compatibility.
