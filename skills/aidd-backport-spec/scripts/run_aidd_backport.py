#!/usr/bin/env python3
"""Resolve and invoke the repository's aidd-backport executable."""

from __future__ import annotations

import os
from pathlib import Path
import shutil
import subprocess
import sys


CLI_NAME = "aidd-backport"


def git_root(start: Path) -> Path | None:
    result = subprocess.run(
        ["git", "-C", str(start), "rev-parse", "--show-toplevel"],
        check=False,
        capture_output=True,
        text=True,
    )
    return Path(result.stdout.strip()).resolve() if result.returncode == 0 else None


def resolve_cli() -> Path:
    configured = os.environ.get("AIDD_TOOLS_HOME")
    if configured:
        candidate = Path(configured).expanduser().resolve() / "bin" / CLI_NAME
        if candidate.is_file() and os.access(candidate, os.X_OK):
            return candidate

    installed = shutil.which(CLI_NAME)
    if installed:
        return Path(installed).resolve()

    roots = filter(
        None,
        (
            git_root(Path.cwd()),
            git_root(Path(__file__).resolve().parent),
        ),
    )
    for root in roots:
        candidate = root / "bin" / CLI_NAME
        if candidate.is_file() and os.access(candidate, os.X_OK):
            return candidate

    raise FileNotFoundError(
        f"Cannot find {CLI_NAME}. Set AIDD_TOOLS_HOME to the aidd-tools repository, "
        f"add {CLI_NAME} to PATH, or run inside its Git worktree."
    )


def main() -> int:
    try:
        cli = resolve_cli()
    except FileNotFoundError as error:
        print(error, file=sys.stderr)
        return 127
    return subprocess.run([str(cli), *sys.argv[1:]], check=False).returncode


if __name__ == "__main__":
    raise SystemExit(main())
