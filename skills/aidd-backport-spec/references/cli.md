# `aidd-backport` CLI

Invoke through `../scripts/run_aidd_backport.py`; it resolves `AIDD_TOOLS_HOME/bin/aidd-backport`, then `PATH`, then the Git root's `bin/aidd-backport`.

## Commands

```text
extract --repo <path> --language <kotlin|typescript> --out <code-facts.json> [--contracts ...] [--allow-build-tool]
validate --facts <json> --model <jsonld> [--repo <path>]
check --model <jsonld> [--bounds <json>] --out <directory>
render --facts <json> --out <markdown>
diff --model <observed.jsonld> --against <accepted.jsonld> --out <diff.json>
run --repo <path> --language <kotlin|typescript> --out <directory> [--model <jsonld>] [--bounds <json>] [--contracts ...]
```

`extract` creates compiler- and contract-backed `code-facts.json`. `validate` checks fact-to-evidence and model links; pass `--repo` to recompute source hashes against the live repository. `check` performs bounded formal checks. `render` creates deterministic `as-built.md`. `diff` compares the recovered model with accepted intent. `run` performs the supported pipeline.

The v1 Kotlin extractor uses compiler PSI for direct syntax facts. It does not provide classpath-backed K2 Analysis API resolution or evaluate the target Gradle build; inferred public types are reported as `UNSUPPORTED / SEMANTIC_CLASSPATH_REQUIRED`. `--allow-build-tool` records permission but does not execute Gradle. The TypeScript extractor uses its built `dist` entrypoint.

Expected output directory:

```text
.aidd/specs/<spec-id>/
  model.jsonld
  model.als
  bounds.json
  verification.json
  manifest.json
  counterexamples/
  code-facts.json
  as-built.md
  candidate-prose.md
```

Keep `as-built.md` deterministic. LLM-written interpretation belongs only in `candidate-prose.md` and remains pending human approval.
