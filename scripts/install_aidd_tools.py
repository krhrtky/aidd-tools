#!/usr/bin/env python3
"""Build and install AIDD CLIs and Codex Skills from a source checkout."""

from __future__ import annotations

import argparse
import os
import re
import shutil
import subprocess
import sys
from pathlib import Path


class InstallationError(RuntimeError):
    pass


class Installer:
    def __init__(self, tools_root: Path, prefix: Path, codex_home: Path):
        self.tools_root = tools_root.resolve()
        self.prefix = prefix.expanduser().resolve()
        self.codex_home = codex_home.expanduser().resolve()

    def check_dependencies(self) -> dict[str, str]:
        versions = {
            "java": self._command_version(["java", "-version"]),
            "node": self._command_version(["node", "--version"]),
            "pnpm": self._command_version(
                ["corepack", "pnpm", "--version"],
                cwd=self.tools_root / "extractors/typescript",
            ),
        }
        self._require_major("java", versions["java"], 21)
        self._require_major("node", versions["node"], 20)
        self._require_major("pnpm", versions["pnpm"], 10)
        return versions

    def build(self) -> None:
        commands = (
            ["./gradlew", "installDist", "--no-daemon"],
            ["./gradlew", "-p", "extractors/kotlin", "installDist", "--no-daemon"],
            ["corepack", "pnpm", "install", "--frozen-lockfile"],
            ["corepack", "pnpm", "build"],
        )
        for command in commands[:2]:
            subprocess.run(command, cwd=self.tools_root, check=True)
        for command in commands[2:]:
            subprocess.run(
                command,
                cwd=self.tools_root / "extractors/typescript",
                check=True,
            )
        subprocess.run(
            [
                str(self.tools_root / "bin/aidd-formalize"),
                "validate",
                "--model",
                str(self.tools_root / "examples/order/model.jsonld"),
            ],
            cwd=self.tools_root,
            check=True,
            stdout=subprocess.DEVNULL,
        )

    def install_links(self, include_skills: bool) -> list[Path]:
        sources = (
            self.tools_root / "bin/aidd-formalize",
            self.tools_root / "bin/aidd-backport",
        )
        links = [
            self._safe_link(source, self.prefix / "bin" / source.name)
            for source in sources
        ]
        if include_skills:
            skills = (
                self.tools_root / "skills/aidd-formalize-spec",
                self.tools_root / "skills/aidd-backport-spec",
            )
            links.extend(
                self._safe_link(source, self.codex_home / "skills" / source.name)
                for source in skills
            )
        return links

    def _safe_link(self, source: Path, destination: Path) -> Path:
        if not source.exists():
            raise InstallationError(f"Installation source does not exist: {source}")
        destination.parent.mkdir(parents=True, exist_ok=True)
        if destination.is_symlink():
            if destination.resolve() == source.resolve():
                return destination
            raise InstallationError(
                f"Refusing to replace existing symbolic link: {destination}",
            )
        if destination.exists():
            raise InstallationError(
                f"Refusing to replace existing user file or directory: {destination}",
            )
        destination.symlink_to(source, target_is_directory=source.is_dir())
        return destination

    def _command_version(self, command: list[str], cwd: Path | None = None) -> str:
        if shutil.which(command[0]) is None:
            raise InstallationError(
                f"Required command is missing: {command[0]}. "
                "Install JDK 21 and Node.js 20 with Corepack first.",
            )
        result = subprocess.run(
            command,
            check=False,
            capture_output=True,
            text=True,
            cwd=cwd,
        )
        output = f"{result.stdout}\n{result.stderr}".strip()
        if result.returncode != 0:
            raise InstallationError(f"Cannot read {command[0]} version: {output}")
        return output.splitlines()[0]

    def _require_major(self, name: str, version: str, expected: int) -> None:
        match = re.search(r"(?<!\d)(\d+)(?:\.\d+)", version)
        if not match or int(match.group(1)) != expected:
            raise InstallationError(
                f"{name} {expected}.x is required, detected: {version}",
            )


def default_tools_root() -> Path:
    return Path(__file__).resolve().parent.parent


def parse_arguments(arguments: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Build and install AIDD command-line tools and Codex Skills.",
    )
    parser.add_argument(
        "--prefix",
        type=Path,
        default=Path("~/.local"),
        help="CLI installation prefix; defaults to ~/.local",
    )
    parser.add_argument(
        "--codex-home",
        type=Path,
        default=Path(os.environ.get("CODEX_HOME", "~/.codex")),
        help="Codex configuration directory; defaults to CODEX_HOME or ~/.codex",
    )
    parser.add_argument(
        "--no-skills",
        action="store_true",
        help="Install only the CLIs",
    )
    parser.add_argument(
        "--skip-build",
        action="store_true",
        help="Link an already-built checkout without rebuilding",
    )
    return parser.parse_args(arguments)


def main(arguments: list[str]) -> int:
    options = parse_arguments(arguments)
    installer = Installer(default_tools_root(), options.prefix, options.codex_home)
    try:
        versions = installer.check_dependencies()
        if not options.skip_build:
            installer.build()
        installed = installer.install_links(include_skills=not options.no_skills)
    except (InstallationError, subprocess.CalledProcessError) as error:
        print(f"Installation failed: {error}", file=sys.stderr)
        return 1

    print("AIDD tools installed successfully.")
    for name, version in versions.items():
        print(f"  {name}: {version}")
    for path in installed:
        print(f"  linked: {path}")
    binary_directory = installer.prefix / "bin"
    path_entries = {Path(entry).expanduser().resolve() for entry in os.environ.get("PATH", "").split(os.pathsep) if entry}
    if binary_directory not in path_entries:
        print()
        print(f'Add this line to your shell profile: export PATH="{binary_directory}:$PATH"')
    if not options.no_skills:
        print("Start a new Codex task to load the installed Skills.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
