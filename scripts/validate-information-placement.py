#!/usr/bin/env python3
"""Validate the repository's How/What/Why-not information placement rules."""

from __future__ import annotations

import re
import sys
import io
import tokenize
from pathlib import Path


ROOT = Path(__file__).resolve().parent.parent
GENERIC_TEST_NAMES = {"test", "works", "charge", "lookup", "happy path"}


def source_files() -> list[Path]:
    roots = (
        ROOT / "src/main",
        ROOT / "extractors/typescript/src",
        ROOT / "extractors/kotlin/src/main",
        ROOT / "scripts",
        ROOT / ".githooks",
    )
    return sorted(
        path
        for root in roots
        for path in root.rglob("*")
        if path.suffix in {".kt", ".ts", ".py", ".sh"} and path.is_file()
        and "tests" not in path.parts
    )


def test_files() -> list[Path]:
    roots = (
        ROOT / "src/test",
        ROOT / "extractors/typescript/test",
        ROOT / "extractors/kotlin/src/test",
        ROOT / "scripts/tests",
    )
    return sorted(
        path
        for root in roots
        for path in root.rglob("*")
        if path.suffix in {".kt", ".ts", ".py"} and path.is_file()
    )


def comments(path: Path, text: str) -> list[str]:
    if path.suffix == ".py":
        return [
            token.string.removeprefix("#").strip()
            for token in tokenize.generate_tokens(io.StringIO(text).readline)
            if token.type == tokenize.COMMENT and not token.string.startswith("#!")
        ]
    if path.suffix == ".sh":
        return [
            line.lstrip().removeprefix("#").strip()
            for line in text.splitlines()
            if line.lstrip().startswith("#") and not line.startswith("#!")
        ]
    found: list[str] = []
    index = 0
    quote: str | None = None
    triple_quote = False
    while index < len(text):
        if quote is not None:
            if triple_quote and text.startswith(quote * 3, index):
                quote = None
                triple_quote = False
                index += 3
            elif text[index] == "\\":
                index += 2
            elif not triple_quote and text[index] == quote:
                quote = None
                index += 1
            else:
                index += 1
            continue
        if text.startswith('"""', index):
            quote = '"'
            triple_quote = True
            index += 3
            continue
        if text[index] in {'"', "'", "`"}:
            quote = text[index]
            index += 1
            continue
        if text.startswith("//", index):
            end = text.find("\n", index)
            end = len(text) if end < 0 else end
            found.append(text[index + 2 : end].strip())
            index = end
            continue
        if text.startswith("/*", index):
            end = text.find("*/", index + 2)
            end = len(text) if end < 0 else end
            found.append(text[index + 2 : end].strip())
            index = min(end + 2, len(text))
            continue
        index += 1
    return found


def mask_kotlin_strings(text: str) -> str:
    output = list(text)
    index = 0
    while index < len(text):
        if text.startswith('"""', index):
            end = text.find('"""', index + 3)
            end = len(text) - 3 if end < 0 else end
            for position in range(index, min(end + 3, len(text))):
                if output[position] != "\n":
                    output[position] = " "
            index = end + 3
            continue
        if text[index] == '"':
            end = index + 1
            while end < len(text):
                if text[end] == "\\":
                    end += 2
                elif text[end] == '"':
                    end += 1
                    break
                else:
                    end += 1
            for position in range(index, min(end, len(text))):
                if output[position] != "\n":
                    output[position] = " "
            index = end
            continue
        index += 1
    return "".join(output)


def typescript_test_names(text: str) -> list[str]:
    names: list[str] = []
    index = 0
    quote: str | None = None
    while index < len(text):
        if quote is not None:
            if text[index] == "\\":
                index += 2
            elif text[index] == quote:
                quote = None
                index += 1
            else:
                index += 1
            continue
        if text[index] in {'"', "'", "`"}:
            quote = text[index]
            index += 1
            continue
        match = re.match(r"(?:test|it)\s*\(\s*([\"'])", text[index:])
        if match:
            delimiter = match.group(1)
            start = index + match.end()
            end = start
            while end < len(text) and text[end] != delimiter:
                end += 2 if text[end] == "\\" else 1
            names.append(text[start:end])
            index = min(end + 1, len(text))
            continue
        index += 1
    return names


def kotlin_test_names(text: str) -> list[str]:
    masked = mask_kotlin_strings(text)
    pattern = re.compile(r"@Test\s+fun\s+(?:`([^`]+)`|([A-Za-z_][A-Za-z0-9_]*))")
    return [first or second for first, second in pattern.findall(masked)]


def python_test_names(text: str) -> list[str]:
    return re.findall(r"(?m)^\s*def\s+(test_[A-Za-z0-9_]+)\s*\(", text)


def descriptive_test_name(name: str) -> bool:
    normalized = re.sub(r"[_-]+", " ", name).strip().lower()
    return len(normalized) >= 8 and normalized not in GENERIC_TEST_NAMES


def validate() -> list[str]:
    errors: list[str] = []
    for path in source_files():
        for comment in comments(path, path.read_text()):
            normalized = re.sub(r"^\*+\s*", "", comment)
            if not normalized.startswith("WHY-NOT:"):
                errors.append(
                    f"{path.relative_to(ROOT)}: code comments must begin with WHY-NOT:",
                )
    for path in test_files():
        content = path.read_text()
        names = {
            ".kt": kotlin_test_names,
            ".ts": typescript_test_names,
            ".py": python_test_names,
        }[path.suffix](content)
        if not names:
            errors.append(f"{path.relative_to(ROOT)}: no test cases were found")
        for name in names:
            if not descriptive_test_name(name):
                errors.append(
                    f"{path.relative_to(ROOT)}: test name does not describe behavior: {name!r}",
                )
    return errors


if __name__ == "__main__":
    failures = validate()
    if failures:
        print("\n".join(failures), file=sys.stderr)
        raise SystemExit(1)
    print("Information placement is valid.")
