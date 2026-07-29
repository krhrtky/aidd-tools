#!/usr/bin/env sh
set -eu

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
tools_root=$(CDPATH= cd -- "$script_dir/.." && pwd)

cd "$tools_root"

python3 scripts/validate-information-placement.py
python3 -m unittest discover -s scripts/tests
./gradlew test jacocoTestReport verifyCriticalSourceCoverage installDist --no-daemon
./gradlew -p extractors/kotlin test installDist --no-daemon
pnpm --dir extractors/typescript test
pnpm --dir extractors/typescript build
scripts/e2e.sh
python3 scripts/quick_validate_skill.py skills/aidd-formalize-spec
python3 scripts/quick_validate_skill.py skills/aidd-backport-spec
git diff --check
