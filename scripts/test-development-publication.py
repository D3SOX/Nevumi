#!/usr/bin/env python3
"""Exercise the development publication step without contacting GitHub."""
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import textwrap
import unittest

ROOT = Path(__file__).resolve().parent.parent


class DevelopmentPublicationTest(unittest.TestCase):
    def publish(self, **overrides):
        workflow = (ROOT / ".github/workflows/development.yml").read_text()
        step = workflow.split("- name: Publish the development prerelease\n", 1)[1]
        script = textwrap.dedent(step.split("        run: |\n", 1)[1])
        with tempfile.TemporaryDirectory(prefix="nevumi-publication-") as temporary:
            directory = Path(temporary)
            (directory / "development").mkdir()
            for name in ["nevumi.apk", "nevumi.pkg.tar.zst", "nevumi.AppImage"]:
                (directory / "development" / name).touch()
            # Both commands record all mutations, including attempts made before a superseded check.
            stub = """
import os
from pathlib import Path
import sys
command = Path(sys.argv[0]).name
arguments = sys.argv[1:]
with open(os.environ['CALLS'], 'a') as log:
    log.write(command + ' ' + ' '.join(arguments) + '\\n')
if command == 'git':
    if os.environ.get('ADVANCE_ON_PUSH'):
        Path(os.environ['ADVANCED']).touch()
    sys.exit(int(os.environ.get('PUSH_FAIL', '0')))
if arguments[0] == 'api':
    if os.environ.get('API_FAIL'):
        sys.exit(1)
    print('newer' if Path(os.environ['ADVANCED']).exists() else os.environ['MASTER_SHA'])
elif arguments[:2] == ['release', 'view']:
    if not os.environ.get('PUBLISHED'):
        sys.exit(1)
    print('false')
elif arguments[:2] == ['release', 'upload'] and os.environ.get('ADVANCE_ON_UPLOAD'):
    Path(os.environ['ADVANCED']).touch()
"""
            for command in ["git", "gh"]:
                executable = directory / command
                executable.write_text(f"#!{sys.executable}\n" + textwrap.dedent(stub))
                executable.chmod(0o755)
            environment = dict(os.environ, PATH=str(directory) + os.pathsep + os.environ["PATH"],
                               GH_REPO="example/nevumi", GITHUB_SHA="current", GITHUB_RUN_ID="123",
                               MASTER_SHA="current", CALLS=str(directory / "calls"),
                               ADVANCED=str(directory / "advanced"))
            environment.update(overrides)
            result = subprocess.run(["bash", "-c", script], cwd=directory, env=environment,
                                    capture_output=True, text=True, timeout=10)
            calls = (directory / "calls").read_text()
            return result, calls

    def test_current_build_publishes_its_tag_and_assets(self):
        result, calls = self.publish()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("git push origin current:refs/tags/development-123", calls)
        self.assertIn("gh release create development-123 --verify-tag --draft --prerelease", calls)
        self.assertIn("gh release upload development-123 --clobber", calls)
        self.assertIn("gh release edit development-123 --draft=false", calls)

    def test_superseded_build_skips_tags_and_releases(self):
        result, calls = self.publish(MASTER_SHA="newer")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("Skipping development publication", result.stdout)
        self.assertNotIn("git push", calls)
        self.assertNotIn("gh release", calls)

    def test_master_advancing_during_tag_creation_is_a_skip(self):
        result, calls = self.publish(ADVANCE_ON_PUSH="1", PUSH_FAIL="1")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("Skipping development publication", result.stdout)
        self.assertNotIn("gh release", calls)

    def test_master_advancing_after_a_successful_tag_skips_the_release(self):
        result, calls = self.publish(ADVANCE_ON_PUSH="1")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("Skipping development publication", result.stdout)
        self.assertNotIn("gh release", calls)

    def test_a_tag_failure_for_the_current_build_still_fails(self):
        result, calls = self.publish(PUSH_FAIL="1")
        self.assertNotEqual(0, result.returncode)
        self.assertNotIn("Skipping", result.stdout)
        self.assertNotIn("gh release", calls)

    def test_master_advancing_during_upload_removes_the_unpublished_draft(self):
        result, calls = self.publish(ADVANCE_ON_UPLOAD="1")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("Skipping development publication", result.stdout)
        self.assertIn("gh release delete development-123 --yes --cleanup-tag", calls)
        self.assertNotIn("gh release edit", calls)

    def test_an_api_failure_is_not_treated_as_a_superseded_build(self):
        result, calls = self.publish(API_FAIL="1")
        self.assertNotEqual(0, result.returncode)
        self.assertNotIn("Skipping", result.stdout)
        self.assertNotIn("git push", calls)

    def test_a_published_rerun_verifies_the_existing_assets(self):
        result, calls = self.publish(PUBLISHED="1")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(3, calls.count("gh release verify-asset development-123"))
        self.assertNotIn("gh release upload", calls)


if __name__ == "__main__":
    unittest.main()
