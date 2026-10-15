"""Offline structural regressions; real Native compiler/runtime acceptance is separate."""
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from contract import FORKS, expected_artifacts, normalize_module, validate
from pipeline import identity, task_set
from publisher import upload_set
from test_publication import fixture


class KtorNativePublicationTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.repo = Path(self.temp.name)
        self.spec = FORKS["ktor"]
        self.version = "3.5.0-kodex.64cf7fdbcf65"
        fixture(self.repo, self.spec, self.version)

    def test_four_native_publications_no_root_or_jvm(self):
        self.assertEqual(set(expected_artifacts(self.spec)),
                         {"ktor-network-" + t.lower() for t in self.spec["targets"]})
        self.assertEqual(sum((task_set("ktor", h) for h in ("linux", "mac", "windows")), []),
                         [":ktor-network:publish" + t + "PublicationToForkStagingRepository"
                          for t in ("LinuxX64", "LinuxArm64", "MacosArm64", "MingwX64")])
        validate(self.repo, self.spec, self.version)

    def test_identity_reads_upstream_version_file(self):
        commit = "64cf7fdbcf65eac0186df0954a03a98b922a52e3"

        def git(*args, **kwargs):
            if args[0] == "ls-tree":
                return f"160000 commit {commit}\tKtor"
            if args[0] == "rev-parse":
                return commit
            if "get-url" in args:
                return "https://github.com/Stream29/ktor.git"
            if args[0] == "show":
                self.assertEqual(args[1], commit + ":VERSION")
                return "3.5.0"
            return ""

        with patch("pipeline.git", side_effect=git), patch("pipeline.run", return_value=""):
            actual = identity("ktor", self.repo)
        self.assertEqual(actual["version"], self.version)

    def test_standalone_component_has_no_unpublished_root_url(self):
        path = next(self.repo.rglob("*linuxx64*.module"))
        doc = json.loads(path.read_bytes())
        doc["component"].update(module="ktor-network", url="../../missing-root.module")
        normalized = json.loads(normalize_module(json.dumps(doc).encode(), self.spec,
                                                self.version, False, "ktor-network-linuxx64"))
        self.assertNotIn("url", normalized["component"])
        self.assertEqual(normalized["component"]["module"], "ktor-network-linuxx64")

    def test_missing_cinterop_is_rejected(self):
        next(self.repo.rglob("*-cinterop-network.klib")).unlink()
        with self.assertRaises(ValueError):
            validate(self.repo, self.spec, self.version)

    def test_official_utils_cannot_be_replaced_by_fork_version(self):
        for path in self.repo.rglob("*.module"):
            doc = json.loads(path.read_bytes())
            for variant in doc["variants"]:
                for dep in variant.get("dependencies", []):
                    if dep["module"] == "ktor-utils":
                        dep["version"]["requires"] = self.version
            path.write_text(json.dumps(doc))
        with self.assertRaisesRegex(ValueError, "Upstream dependency version changed"):
            validate(self.repo, self.spec, self.version)

    def test_manifest_is_attached_to_a_published_native_coordinate(self):
        doc = {"identity": {"version": self.version}}
        (self.repo / "manifest.json").write_text(json.dumps(doc))
        with patch("publisher.verify_bundle", return_value=(doc, {})):
            _, anchor = upload_set(self.repo, "ktor")
        self.assertIn("/ktor-network-linuxarm64/", anchor)
        self.assertNotIn("/ktor-network/", anchor)
