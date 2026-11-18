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
        # The step ends where the next step of the job starts.
        script = textwrap.dedent(step.split("        run: |\n", 1)[1].split("\n      - ", 1)[0].split("\n      # ", 1)[0])
        with tempfile.TemporaryDirectory(prefix="nevumi-publication-") as temporary:
            directory = Path(temporary)
            (directory / "development").mkdir()
            for name in ["nevumi.apk", "nevumi.pkg.tar.zst", "nevumi.AppImage"]:
                (directory / "development" / name).touch()
            stub = """
import os
from pathlib import Path
import sys
command = Path(sys.argv[0]).name
arguments = sys.argv[1:]
with open(os.environ['CALLS'], 'a') as log:
    log.write(command + ' ' + ' '.join(arguments) + '\\n')
if command == 'git':
    sys.exit(int(os.environ.get('PUSH_FAIL', '0')))
if arguments[:2] == ['release', 'view']:
    if not os.environ.get('PUBLISHED'):
        sys.exit(1)
    print('false')
"""
            for command in ["git", "gh"]:
                executable = directory / command
                executable.write_text(f"#!{sys.executable}\n" + textwrap.dedent(stub))
                executable.chmod(0o755)
            environment = dict(os.environ, PATH=str(directory) + os.pathsep + os.environ["PATH"],
                               GH_REPO="example/nevumi", GITHUB_SHA="current", TAG="development-123",
                               CALLS=str(directory / "calls"))
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

    def test_a_tag_failure_stops_the_publication(self):
        result, calls = self.publish(PUSH_FAIL="1")
        self.assertNotEqual(0, result.returncode)
        self.assertNotIn("gh release", calls)

    def test_a_published_rerun_verifies_the_existing_assets(self):
        result, calls = self.publish(PUBLISHED="1")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(3, calls.count("gh release verify-asset development-123"))
        self.assertNotIn("gh release upload", calls)


class DevelopmentPruningTest(unittest.TestCase):
    def test_only_the_current_development_prerelease_remains(self):
        workflow = (ROOT / ".github/workflows/development.yml").read_text()
        step = workflow.split("- name: Remove older development prereleases\n", 1)[1]
        script = textwrap.dedent(step.split("        run: |\n", 1)[1].split("\n      - ", 1)[0])
        with tempfile.TemporaryDirectory(prefix="nevumi-pruning-") as temporary:
            directory = Path(temporary)
            # gh applies the --jq filter itself, so the stub prints the matching tags.
            releases = "".join(f"development-{run}\\n" for run in [3, 2, 1])
            (directory / "gh").write_text(f"""#!{sys.executable}
import os, sys
if sys.argv[1] == 'api':
    sys.stdout.write("{releases}")
else:
    with open(os.environ['CALLS'], 'a') as log:
        log.write(' '.join(sys.argv[1:]) + '\\n')
""")
            (directory / "gh").chmod(0o755)
            calls = directory / "calls"
            calls.touch()
            environment = dict(os.environ, PATH=str(directory) + os.pathsep + os.environ["PATH"],
                               GH_REPO="example/nevumi", TAG="development-2", CALLS=str(calls))
            result = subprocess.run(["bash", "-c", script], cwd=directory, env=environment,
                                    capture_output=True, text=True, timeout=10)
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual(["release delete development-3 --yes --cleanup-tag",
                              "release delete development-1 --yes --cleanup-tag"], calls.read_text().splitlines())


if __name__ == "__main__":
    unittest.main()
