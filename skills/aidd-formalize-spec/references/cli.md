# `aidd-formalize` CLI

Invoke through `../scripts/run_aidd_formalize.py`; it resolves `AIDD_TOOLS_HOME/bin/aidd-formalize`, then `PATH`, then the Git root's `bin/aidd-formalize`.

## Commands

```text
validate --model <jsonld>
explore --model <jsonld> [--bounds <json>] --out <directory>
check --model <jsonld> [--bounds <json>] --out <directory>
render --model <jsonld> --out <markdown>
run --model <jsonld> [--bounds <json>] --out <directory>
```

`validate` checks schema, types, references, evidence, and graph consistency. `check` emits Alloy and normalized verification artifacts. `render` creates the deterministic specification view. `run` performs the supported pipeline.

`explore` includes `accepted` claims as premises and `candidate` claims as the exploration target. It excludes `rejected` claims. Because candidates are not approved semantics, its top-level status is always `PROVISIONAL`; inspect `boundedOutcome` for the finite Alloy result. It exits `2` for a counterexample or no instance, `3` for completed candidate exploration, `4` for timeout or unsupported input, and `5` when human review is required.

Expected output directory:

```text
.aidd/specs/<spec-id>/
  model.jsonld
  model.als
  bounds.json
  verification.json
  manifest.json
  counterexamples/
  spec.md
```

Do not edit generated `model.als`, `verification.json`, `manifest.json`, or counterexamples to change a result. Correct the canonical model or reviewed bounds and rerun.

Candidate exploration instead writes:

```text
.aidd/specs/<spec-id>/
  model.jsonld
  model.als
  bounds.json
  verification.json
  manifest.json
  counterexamples/
  candidate-spec.md
```

The exploration manifest records `mode: candidate-exploration`, sorted target claim IDs, status counts, bounds, assumptions, and artifact hashes.
