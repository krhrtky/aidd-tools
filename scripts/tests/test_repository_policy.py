import importlib.util
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]


def load_module(name: str, path: Path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    spec.loader.exec_module(module)
    return module


placement = load_module(
    "validate_information_placement",
    ROOT / "scripts/validate-information-placement.py",
)
commit_message = load_module(
    "validate_commit_message",
    ROOT / "scripts/validate-commit-message.py",
)


class InformationPlacementTest(unittest.TestCase):
    def test_typescript_names_exclude_test_calls_inside_template_fixtures(self):
        source = '''
test("describes observable behavior", () => {});
const fixture = `test("charge", () => {});`;
'''

        self.assertEqual(
            ["describes observable behavior"],
            placement.typescript_test_names(source),
        )

    def test_comments_ignore_comment_markers_inside_strings(self):
        source = '''
const address = "https://example.test";
// WHY-NOT: CWD is an untrusted execution boundary.
'''

        self.assertEqual(
            ["WHY-NOT: CWD is an untrusted execution boundary."],
            placement.comments(Path("source.ts"), source),
        )

    def test_generic_test_names_are_rejected(self):
        self.assertFalse(placement.descriptive_test_name("works"))
        self.assertTrue(
            placement.descriptive_test_name("unapproved bounds remain provisional"),
        )


class CommitMessageTest(unittest.TestCase):
    def test_change_reason_is_required(self):
        self.assertIsNotNone(commit_message.validate("fix: reject invalid model\n"))
        self.assertIsNone(
            commit_message.validate(
                "fix: reject invalid model\n\n"
                "Why: invalid models must not produce accepted artifacts.\n",
            ),
        )


if __name__ == "__main__":
    unittest.main()
