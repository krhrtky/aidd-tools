#!/usr/bin/env sh
set -eu

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
tools_root=$(CDPATH= cd -- "$script_dir/.." && pwd)

git -C "$tools_root" config core.hooksPath .githooks
echo "Git hooks enabled from .githooks"

