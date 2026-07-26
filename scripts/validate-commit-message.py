#!/usr/bin/env python3
"""Require commit messages to retain the reason for a change."""

from __future__ import annotations

import re
import sys
from pathlib import Path


def validate(message: str) -> str | None:
    retained = "\n".join(
        line for line in message.splitlines() if not line.lstrip().startswith("#")
    ).strip()
    if not retained:
        return "commit message is empty"
    subject = retained.splitlines()[0]
    if subject.startswith(("Merge ", "Revert ", "fixup! ", "squash! ")):
        return None
    if len(subject) > 72:
        return "commit subject must be at most 72 characters"
    if not re.search(r"(?m)^Why:\s+\S", retained):
        return "commit message body must contain a non-empty 'Why:' paragraph"
    return None


if __name__ == "__main__":
    if len(sys.argv) != 2:
        print("Usage: validate-commit-message.py <commit-message-file>", file=sys.stderr)
        raise SystemExit(2)
    failure = validate(Path(sys.argv[1]).read_text())
    if failure:
        print(failure, file=sys.stderr)
        raise SystemExit(1)

