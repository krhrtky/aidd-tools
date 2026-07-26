# `aidd-formalize` CLI

Invoke through `../scripts/run_aidd_formalize.py`; it resolves `AIDD_TOOLS_HOME/bin/aidd-formalize`, then `PATH`, then the Git root's `bin/aidd-formalize`.

## Commands

```text
validate --model <jsonld>
check --model <jsonld> [--bounds <json>] --out <directory>
render --model <jsonld> --out <markdown>
run --model <jsonld> [--bounds <json>] --out <directory>
```

`validate` checks schema, types, references, evidence, and graph consistency. `check` emits Alloy and normalized verification artifacts. `render` creates the deterministic specification view. `run` performs the supported pipeline.

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
