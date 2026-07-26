#!/usr/bin/env sh
set -eu

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
tools_root=$(CDPATH= cd -- "$script_dir/.." && pwd)
artifact_root=$(mktemp -d "${TMPDIR:-/tmp}/aidd-e2e.XXXXXX")

cd "$tools_root"

bin/aidd-formalize check \
  --model examples/order/model.jsonld \
  --bounds examples/order/bounds.json \
  --out "$artifact_root/formal"

node -e '
  const fs = require("fs");
  const result = JSON.parse(fs.readFileSync(process.argv[1], "utf8"));
  if (result.status !== "NO_COUNTEREXAMPLE_WITHIN_SCOPE") process.exit(1);
' "$artifact_root/formal/verification.json"

find "$artifact_root/formal" -type f -print0 | sort -z | xargs -0 shasum -a 256 > "$artifact_root/first.sha256"
bin/aidd-formalize check \
  --model examples/order/model.jsonld \
  --bounds examples/order/bounds.json \
  --out "$artifact_root/formal"
find "$artifact_root/formal" -type f -print0 | sort -z | xargs -0 shasum -a 256 > "$artifact_root/second.sha256"
diff -u "$artifact_root/first.sha256" "$artifact_root/second.sha256"

bin/aidd-formalize validate \
  --model examples/pure-function/model.jsonld > "$artifact_root/candidate-validation.json"
node -e '
  const fs = require("fs");
  const result = JSON.parse(fs.readFileSync(process.argv[1], "utf8"));
  if (!result.valid) process.exit(1);
' "$artifact_root/candidate-validation.json"

candidate_status=0
bin/aidd-formalize explore \
  --model examples/pure-function/model.jsonld \
  --bounds examples/pure-function/bounds.json \
  --out "$artifact_root/candidate" || candidate_status=$?
test "$candidate_status" -eq 3
node -e '
  const fs = require("fs");
  const verification = JSON.parse(fs.readFileSync(process.argv[1], "utf8"));
  const manifest = JSON.parse(fs.readFileSync(process.argv[2], "utf8"));
  if (verification.status !== "PROVISIONAL") process.exit(1);
  if (verification.boundedOutcome !== "NO_COUNTEREXAMPLE_WITHIN_SCOPE") process.exit(1);
  if (manifest.mode !== "candidate-exploration") process.exit(1);
  if (!manifest.targetClaimIds || manifest.targetClaimIds.length === 0) process.exit(1);
  if (!manifest.claimStatusCounts || manifest.claimStatusCounts.candidate === 0) process.exit(1);
' "$artifact_root/candidate/verification.json" "$artifact_root/candidate/manifest.json"
test -s "$artifact_root/candidate/candidate-spec.md"

find "$artifact_root/candidate" -type f -print0 | sort -z | xargs -0 shasum -a 256 > "$artifact_root/candidate-first.sha256"
candidate_status=0
bin/aidd-formalize explore \
  --model examples/pure-function/model.jsonld \
  --bounds examples/pure-function/bounds.json \
  --out "$artifact_root/candidate" || candidate_status=$?
test "$candidate_status" -eq 3
find "$artifact_root/candidate" -type f -print0 | sort -z | xargs -0 shasum -a 256 > "$artifact_root/candidate-second.sha256"
diff -u "$artifact_root/candidate-first.sha256" "$artifact_root/candidate-second.sha256"

bin/aidd-backport extract \
  --repo examples/typescript \
  --language typescript \
  --out "$artifact_root/typescript"
bin/aidd-backport render \
  --facts "$artifact_root/typescript/code-facts.json" \
  --out "$artifact_root/typescript/as-built.md"

bin/aidd-backport extract \
  --repo examples/kotlin \
  --language kotlin \
  --out "$artifact_root/kotlin"

test -s "$artifact_root/typescript/as-built.md"
test -s "$artifact_root/kotlin/code-facts.json"

./install.sh \
  --skip-build \
  --no-skills \
  --prefix "$artifact_root/install"
"$artifact_root/install/bin/aidd-formalize" validate \
  --model examples/order/model.jsonld > "$artifact_root/installed-validation.json"
node -e '
  const fs = require("fs");
  const result = JSON.parse(fs.readFileSync(process.argv[1], "utf8"));
  if (!result.valid) process.exit(1);
' "$artifact_root/installed-validation.json"
