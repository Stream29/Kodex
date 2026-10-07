"""Trusted publication admission and workflow syntax boundaries; no package writes."""
import os
import unittest
from pathlib import Path
from unittest.mock import patch

from pipeline import require_trusted_event


class AdmissionTests(unittest.TestCase):
    def test_unprotected_push_and_unreviewed_dispatch_are_rejected(self):
        for environment in (
            {"GITHUB_EVENT_NAME": "push", "GITHUB_REF_PROTECTED": "false"},
            {"GITHUB_EVENT_NAME": "push"},
            {"GITHUB_EVENT_NAME": "workflow_dispatch", "FORK_PUBLICATION_REVIEWED": "false"},
            {"GITHUB_EVENT_NAME": "pull_request", "FORK_PUBLICATION_REVIEWED": "true"},
        ):
            with self.subTest(environment=environment), patch.dict(os.environ, environment, clear=True):
                with self.assertRaises(ValueError):
                    require_trusted_event()

    def test_protected_push_and_reviewed_manual_dispatch_are_admitted(self):
        for environment in (
            {"GITHUB_EVENT_NAME": "push", "GITHUB_REF_PROTECTED": "true"},
            {"GITHUB_EVENT_NAME": "workflow_dispatch", "FORK_PUBLICATION_REVIEWED": "true"},
        ):
            with self.subTest(environment=environment), patch.dict(os.environ, environment, clear=True):
                require_trusted_event()

    def test_scan_optout_is_a_gradle_startup_argument(self):
        root = Path(__file__).resolve().parent
        for name in ("pipeline.py", "smoke.py"):
            self.assertIn('"--no-scan"', (root / name).read_text())
        self.assertNotIn("forkPublicationVersion", (root / "publish.init.gradle").read_text())

    def test_workflow_requires_review_and_has_no_runner_job_env(self):
        root = Path(__file__).resolve().parents[2]
        for fork in ("mosaic", "mcp", "lucene"):
            text = (root / ".github/workflows" / f"fork-packages-{fork}.yml").read_text()
            self.assertIn("github.event_name == 'push' && github.ref_protected", text)
            self.assertIn("FORK_PUBLICATION_REVIEWED:", text)
            # The actual actionlint check runs separately on Xiaoxin; this prevents regression
            # to the exact invalid indentation in the first handoff.
            self.assertNotRegex(text, r"(?m)^      GRADLE_USER_HOME:")
            self.assertNotRegex(text, r"(?m)^      KONAN_DATA_DIR:")

    def test_cklib_binding_and_lucene_property_are_explicit(self):
        # Static recipe guards, not a replacement for the three-host producer.
        root = Path(__file__).resolve().parent
        init = (root / "publish.init.gradle").read_text()
        self.assertIn("config.konanHome = home.canonicalPath", init)
        self.assertIn("System.getenv('KONAN_DATA_DIR')", init)
        self.assertIn("dependsOn(p.tasks.named('downloadKotlinNativeDistribution'))", init)
        self.assertIn('if args.fork != "lucene" else []',
                      (root / "pipeline.py").read_text())


if __name__ == "__main__":
    unittest.main()
