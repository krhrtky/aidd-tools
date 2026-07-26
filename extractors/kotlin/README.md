# AIDD Kotlin Extractor

`aidd-kotlin-extractor` statically extracts deterministic, source-backed facts
from Kotlin repositories. It never runs the target application's code or its
tests.

## Build and run

Requirements:

- JDK 21
- Gradle 8.5 or newer

```bash
gradle installDist
build/install/aidd-kotlin-extractor/bin/aidd-kotlin-extractor \
  --repo /path/to/repository \
  --out /path/to/code-facts.json
```

The optional `--allow-build-tool` flag records that evaluating build
configuration is permitted. The v1 extractor does not yet evaluate Gradle even
when this flag is present. This preserves the safety boundary while leaving a
stable CLI for a future Analysis API classpath resolver.

## Extracted facts

- Public classes, interfaces, objects, type aliases, functions, and properties
- `data`, `sealed`, and `enum` type structure
- Explicit parameter and return types, including nullability
- `require`, `check`, and explicit `throw` guards
- `when` branches as transition candidates
- Local and binary assignments and call sites
- Test functions and assertion calls
- `@aidd.requirement` and `@aidd.verifies` KDoc links

Every fact is `accepted` / `observed` because it comes directly from compiler
PSI. Facts and diagnostics contain repository-relative, one-based source spans
and a SHA-256 of the complete source file.

## Determinism and safety

Inputs are sorted by normalized repository-relative path. Generated fact IDs,
the repository hash, fact order, map keys, and JSON formatting are stable for
the same Kotlin source tree. `.git`, `.gradle`, `build`, `out`, and
`node_modules` directories are excluded.

Gradle scripts, compiler plugins, annotation processors, application entry
points, and tests in the target repository are never executed.

## Feasibility boundary

The extractor uses the Kotlin 2.3.21 compiler's PSI parser. Kotlin's embedded
`KotlinCoreEnvironment` is currently marked as a K1 compatibility API; the
syntax tree itself is shared compiler infrastructure, but this is not K2
Analysis API semantic resolution.

Public expression-bodied functions and properties without explicit types may
depend on the module classpath. They are still recorded syntactically and also
produce:

```text
UNSUPPORTED / SEMANTIC_CLASSPATH_REQUIRED
```

Malformed source similarly produces `UNSUPPORTED / KOTLIN_SYNTAX_ERROR` while
other files continue to be extracted. Calls through reflection, compiler
plugins, generated sources, delegation semantics, and overload resolution are
not claimed as semantically resolved.
