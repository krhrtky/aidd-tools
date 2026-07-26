# TypeScript CodeFacts extractor

Deterministically extracts observed facts from a TypeScript project without
executing target code.

```sh
pnpm install
pnpm build
node dist/cli.js \
  --repo /path/to/project \
  --contracts contracts/openapi.yaml \
  --contracts contracts/domain.schema.json \
  --out code-facts.json
```

The project must contain a `tsconfig.json`. The extractor uses the TypeScript
6.0.3 Compiler API for symbol and type resolution. Dynamic or unresolved calls
are emitted as diagnostics rather than accepted facts.
