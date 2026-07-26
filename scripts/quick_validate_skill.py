#!/usr/bin/env python3
"""Repository copy of Codex skill-creator's minimal validator."""

import re
import sys
from pathlib import Path

def validate_skill(skill_path: Path) -> tuple[bool, str]:
    skill_md = skill_path / "SKILL.md"
    if not skill_md.exists():
        return False, "SKILL.md not found"
    content = skill_md.read_text()
    match = re.match(r"^---\n(.*?)\n---", content, re.DOTALL)
    if not match:
        return False, "Invalid YAML frontmatter format"
    frontmatter: dict[str, str] = {}
    for line in match.group(1).splitlines():
        if ":" not in line:
            return False, f"Invalid frontmatter line: {line}"
        key, value = line.split(":", 1)
        key = key.strip()
        if key in frontmatter:
            return False, f"Duplicate frontmatter key: {key}"
        frontmatter[key] = value.strip()
    if set(frontmatter) != {"name", "description"}:
        return False, "Frontmatter must contain only name and description"
    name = frontmatter["name"]
    description = frontmatter["description"]
    if not isinstance(name, str) or not re.fullmatch(r"[a-z0-9]+(?:-[a-z0-9]+)*", name):
        return False, "Skill name must be hyphen-case"
    if len(name) > 64:
        return False, "Skill name exceeds 64 characters"
    if not isinstance(description, str) or not description.strip() or len(description) > 1024:
        return False, "Skill description must contain 1-1024 characters"
    if "<" in description or ">" in description:
        return False, "Skill description cannot contain angle brackets"
    return True, "Skill is valid!"


if __name__ == "__main__":
    if len(sys.argv) != 2:
        print("Usage: quick_validate_skill.py <skill_directory>")
        raise SystemExit(1)
    valid, message = validate_skill(Path(sys.argv[1]))
    print(message)
    raise SystemExit(0 if valid else 1)
