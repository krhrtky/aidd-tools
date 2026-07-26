#!/usr/bin/env python3
"""Repository copy of Codex skill-creator's minimal validator."""

import re
import sys
from pathlib import Path

import yaml


def validate_skill(skill_path: Path) -> tuple[bool, str]:
    skill_md = skill_path / "SKILL.md"
    if not skill_md.exists():
        return False, "SKILL.md not found"
    content = skill_md.read_text()
    match = re.match(r"^---\n(.*?)\n---", content, re.DOTALL)
    if not match:
        return False, "Invalid YAML frontmatter format"
    try:
        frontmatter = yaml.safe_load(match.group(1))
    except yaml.YAMLError as error:
        return False, f"Invalid YAML: {error}"
    if not isinstance(frontmatter, dict):
        return False, "Frontmatter must be a mapping"
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

