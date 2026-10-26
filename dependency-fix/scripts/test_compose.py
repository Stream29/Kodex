import json
import tempfile
import unittest
from pathlib import Path

import compose


class ComposePublicationTest(unittest.TestCase):
    def test_requires_all_four_real_regressions(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            self.write_report(root, compose.REGRESSIONS)
            self.assertEqual({"tests.xml"}, set(compose.verify_regressions(root)))
            self.write_report(root, set(compose.REGRESSIONS) - {next(iter(compose.REGRESSIONS))})
            with self.assertRaisesRegex(ValueError, "four"):
                compose.verify_regressions(root)

    def test_rejects_failures_and_skips(self):
        for failure in ("failure", "error", "skipped"):
            with self.subTest(failure=failure), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                self.write_report(root, compose.REGRESSIONS, f"<{failure}/>")
                with self.assertRaisesRegex(ValueError, "Failed or skipped"):
                    compose.verify_regressions(root)

    def test_rejects_duplicate_regressions(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            self.write_report(root, [*compose.REGRESSIONS, next(iter(compose.REGRESSIONS))])
            with self.assertRaisesRegex(ValueError, "Duplicate"):
                compose.verify_regressions(root)

    def test_bundle_has_exact_five_payloads_and_checksum_closure(self):
        with tempfile.TemporaryDirectory() as temporary:
            root, identity = self.bundle(Path(temporary))
            expected, marker = compose.payload(root, identity)
            self.assertEqual(30, len(expected))
            self.assertIn(marker, expected)
            self.assertEqual(compose.digest(expected[marker]).encode(), expected[marker + ".sha256"])

    def test_rejects_unexpected_files_or_changed_payload(self):
        with tempfile.TemporaryDirectory() as temporary:
            root, identity = self.bundle(Path(temporary))
            extra = root / "repo/unreviewed.jar"
            extra.write_bytes(b"not reviewed")
            with self.assertRaisesRegex(ValueError, "Unexpected bundle files"):
                compose.payload(root, identity)
            extra.unlink()
            next((root / "repo").rglob("*.klib")).write_bytes(b"changed")
            with self.assertRaisesRegex(ValueError, "digest"):
                compose.payload(root, identity)

    def test_rejects_source_mismatch_and_root_redirect(self):
        with tempfile.TemporaryDirectory() as temporary:
            root, identity = self.bundle(Path(temporary))
            with self.assertRaisesRegex(ValueError, "identity mismatch"):
                compose.payload(root, {**identity, "commit": "b" * 40})
            metadata = next((root / "repo").rglob("*.module"))
            doc = json.loads(metadata.read_text())
            doc["component"]["url"] = "../../ui/unpublished.module"
            data = json.dumps(doc).encode()
            metadata.write_bytes(data)
            manifest_path = root / "bundle.json"
            manifest = json.loads(manifest_path.read_text())
            manifest["files"][metadata.relative_to(root / "repo").as_posix()] = {
                "size": len(data), "sha256": compose.digest(data),
            }
            manifest_path.write_text(json.dumps(manifest))
            with self.assertRaisesRegex(ValueError, "self-contained"):
                compose.payload(root, identity)

    @staticmethod
    def write_report(root, methods, child=""):
        cases = "".join(f'<testcase name="{name}[Chrome]">{child}</testcase>' for name in methods)
        (root / "tests.xml").write_text(f"<testsuite>{cases}</testsuite>")

    @staticmethod
    def bundle(root):
        identity = {
            "sourcePath": compose.SOURCE, "repository": compose.REPOSITORY,
            "commit": "a" * 40, "tree": "b" * 40, "version": "1.11.1-kodex." + "a" * 12,
        }
        version = identity["version"]
        stem = f"ui-wasm-js-{version}"
        base = root / "repo" / compose.GROUP_PATH / version
        base.mkdir(parents=True)
        module = {
            "component": {
                "group": "org.jetbrains.compose.ui", "module": "ui-wasm-js", "version": version,
            },
            "variants": [],
        }
        data = {f"{stem}.{suffix}": suffix.encode() for suffix in ("pom", "klib")}
        data[f"{stem}.module"] = json.dumps(module).encode()
        data[f"{stem}-sources.jar"] = b"fixture-sources"
        data[f"{stem}-kotlin_resources.kotlin_resources.zip"] = b"fixture-resources"
        files = {}
        for name, content in data.items():
            path = base / name
            path.write_bytes(content)
            files[path.relative_to(root / "repo").as_posix()] = {
                "size": len(content), "sha256": compose.digest(content),
            }
        (root / "bundle.json").write_text(json.dumps({
            "identity": identity, "files": files, "regressions": {"tests.xml": "fixture"},
            "testMethods": sorted(compose.REGRESSIONS),
        }))
        return root, identity


if __name__ == "__main__":
    unittest.main()
