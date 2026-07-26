import tempfile
import unittest
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from install_aidd_tools import Installer, InstallationError


class InstallerTest(unittest.TestCase):
    def setUp(self):
        self.temporary_directory = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary_directory.name)
        self.tools_root = self.root / "tools"
        self.prefix = self.root / "prefix"
        self.codex_home = self.root / "codex"
        for relative in (
            "bin/aidd-formalize",
            "bin/aidd-backport",
            "skills/aidd-formalize-spec/SKILL.md",
            "skills/aidd-backport-spec/SKILL.md",
        ):
            target = self.tools_root / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text("fixture")

    def tearDown(self):
        self.temporary_directory.cleanup()

    def test_installation_links_both_clis_and_codex_skills(self):
        installer = Installer(self.tools_root, self.prefix, self.codex_home)

        installed = installer.install_links(include_skills=True)

        self.assertEqual(4, len(installed))
        self.assertEqual(
            (self.tools_root / "bin/aidd-formalize").resolve(),
            (self.prefix / "bin/aidd-formalize").resolve(),
        )
        self.assertEqual(
            (self.tools_root / "skills/aidd-backport-spec").resolve(),
            (self.codex_home / "skills/aidd-backport-spec").resolve(),
        )

    def test_repeated_installation_keeps_matching_links(self):
        installer = Installer(self.tools_root, self.prefix, self.codex_home)

        first = installer.install_links(include_skills=True)
        second = installer.install_links(include_skills=True)

        self.assertEqual(first, second)

    def test_installation_refuses_to_replace_an_existing_user_file(self):
        conflicting = self.prefix / "bin/aidd-formalize"
        conflicting.parent.mkdir(parents=True)
        conflicting.write_text("user-owned")
        installer = Installer(self.tools_root, self.prefix, self.codex_home)

        with self.assertRaises(InstallationError):
            installer.install_links(include_skills=False)

        self.assertEqual("user-owned", conflicting.read_text())


if __name__ == "__main__":
    unittest.main()
