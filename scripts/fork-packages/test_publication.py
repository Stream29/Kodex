"""Offline fixtures only. No Gradle, Git, credentials or published-version tests."""
import base64
import hashlib
import io
import json
import tarfile
import tempfile
import threading
import unittest
import zipfile
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

from contract import (FORKS, HOSTS, TARGETS, coordinate, digest, encoded, files, host_targets,
                      normalize_module, safe_name, smoke_gates, target_closure, target_set, validate)
from pipeline import (compiler_archive_evidence, extract_source, guard, identity, live_main,
                      mcp_compiler_evidence, merge, prepare_android, recipe,
                      task_set, verify_bundle, write_json)
from publisher import MavenHTTP, classify, publish, upload_set, verify_receipts
from smoke import node_environment, smoke


def archive(entries):
    """Deliberately small structural fixture, never used as a publication recipe."""
    out = io.BytesIO()
    with zipfile.ZipFile(out, "w") as zipped:
        for name, content in entries.items():
            zipped.writestr(name, content)
    return out.getvalue()


def fixture(repo, spec, version):
    """Unpublished offline model of KMP layout; these bytes are NOT compiler output."""
    result = {}
    native_names = {"linuxX64": "linux_x64", "linuxArm64": "linux_arm64",
                    "macosArm64": "macos_arm64", "mingwX64": "mingw_x64"}
    edges = {"mosaic-runtime": (["mosaic-terminal"], ["mosaic-tty-terminal"]),
             "mosaic-animation": (["mosaic-runtime"], []),
             "mosaic-testing": (["mosaic-runtime"], []),
             "mosaic-tty-terminal": (["mosaic-terminal", "mosaic-tty"], []),
             "kotlin-sdk-client": (["kotlin-sdk-core"], []),
             "kotlin-sdk-server": (["kotlin-sdk-core"], [])}

    def attributes(target, role):
        attrs = {"org.jetbrains.kotlin.platform.type": (
            "common" if target == "root" else "native" if target in native_names else target),
            "org.gradle.category": "documentation" if role == "sources" else "library",
            "org.gradle.jvm.environment": "standard-jvm" if target == "jvm" else "non-jvm",
            "org.gradle.usage": (
                "java-runtime" if target == "jvm" and role != "api" else
                "java-api" if target == "jvm" else
                "kotlin-runtime" if role in ("runtime", "sources") else
                "kotlin-metadata" if target == "root" or role == "metadata" else "kotlin-api")}
        if role == "sources":
            attrs["org.gradle.docstype"] = "sources"
            attrs["org.gradle.dependency.bundling"] = "external"
        if target == "jvm":
            attrs["org.gradle.libraryelements"] = "jar"
        elif target == "js":
            attrs["org.jetbrains.kotlin.js.compiler"] = "ir"
        elif target in native_names:
            attrs["org.jetbrains.kotlin.native.target"] = native_names[target]
        return attrs

    def roles(target):
        return (["api", "runtime", "sources"] if target in ("jvm", "js") else
                ["api", "sources", "metadata"] if target == "macosArm64" else ["api", "sources"])

    for base in spec["modules"]:
        api, implementation = edges.get(base, ([], []))
        for target in ("root",) + target_set(spec):
            artifact = base if target == "root" else f"{base}-{target.lower()}"
            parent = coordinate(spec, artifact, version)
            stem = f"{artifact}-{version}"
            dependency = "<dependencies>" + "".join(
                "<dependency>" + f"<groupId>{spec['group']}</groupId>"
                f"<artifactId>{d if target == 'root' else d + '-' + target.lower()}</artifactId>"
                f"<version>{version}</version><scope>"
                f"{'runtime' if target == 'root' or (target in ('jvm', 'js') and d in implementation) else 'compile'}"
                "</scope>"
                "</dependency>" for d in api + implementation) + "</dependencies>"
            result[f"{parent}/{stem}.pom"] = (
                '<project xmlns="http://maven.apache.org/POM/4.0.0">'
                f"<groupId>{spec['group']}</groupId><artifactId>{artifact}</artifactId>"
                f"<version>{version}</version>{dependency}</project>").encode()
            ext = ".jar" if target in ("root", "jvm") else ".klib"
            content = {"Probe.class": b"\xca\xfe\xba\xbe\x00\x00\x00\x34offline model"}
            if target == "root":
                structure = {"projectStructure": {
                    "formatVersion": "0.3.3", "isPublishedAsRoot": "true",
                    "variants": [{"name": t + "ApiElements", "sourceSet": ["commonMain"]}
                                 for t in target_set(spec)],
                    "sourceSets": [{"name": "commonMain", "dependsOn": [],
                                    "moduleDependency": [spec["group"] + ":" + d for d in api + implementation],
                                    "binaryLayout": "klib"}]}}
                content = {"META-INF/kotlin-project-structure-metadata.json": encoded(structure),
                           "commonMain/default/manifest": f"compiler_version=2.4.0\nunique_name={base}_commonMain\n",
                           "commonMain/default/linkdata/module": b"offline model",
                           "commonMain/default/linkdata/package_probe/0_probe.knm": b"offline metadata model"}
            elif target in native_names:
                content = {"default/manifest": (f"native_targets={native_names[target]}\n"
                                                "builtins_platform=NATIVE\ncompiler_version=2.4.0\n"),
                           "default/ir/bodies.knb": b"offline Native IR model",
                           "default/linkdata/module": b"offline Native metadata model"}
                if base == "mosaic-tty":
                    content["default/targets/" + native_names[target] + "/native/mosaic.bc"] = b"offline bitcode model"
            elif target == "js":
                content = {"default/manifest": "builtins_platform=JS\ncompiler_version=2.4.0\n",
                           "default/ir/bodies.knb": b"fixture IR, never published",
                           "default/linkdata/module": b"fixture metadata, never published"}
            if base == "mosaic-tty" and target == "jvm":
                package = "com/jakewharton/mosaic/tty/"
                content = {"META-INF/MANIFEST.MF": "Manifest-Version: 1.0\r\nMulti-Release: true\r\n\r\n"}
                for arch, lib in (("aarch64", "libmosaic.so"), ("riscv64", "libmosaic.so"),
                                  ("amd64", "libmosaic.so"), ("aarch64", "libmosaic.dylib"),
                                  ("x86_64", "libmosaic.dylib"), ("aarch64", "mosaic.dll"),
                                  ("amd64", "mosaic.dll")):
                    content[package + f"jni/{arch}/{lib}"] = b"offline native model"
                for name in ("Jni", "NativeLibrary"):
                    content[package + name + ".class"] = b"\xca\xfe\xba\xbe\x00\x00\x00\x34offline class model"
                for name in ("Libmosaic", "Tty", "TestTerminal", "StandardStreams"):
                    content["META-INF/versions/22/" + package + name + ".class"] = \
                        b"\xca\xfe\xba\xbe\x00\x00\x00\x42offline class model"
            result[f"{parent}/{stem}{ext}"] = archive(content)
            result[f"{parent}/{stem}-sources.jar"] = archive({
                "commonMain/probe/Probe.kt": "package probe\nclass Probe // offline source model\n"})
            if target == "macosArm64":
                # Genuine host metadata attachments may be manifest-only (no shared Apple sources).
                result[f"{parent}/{stem}-metadata.jar"] = archive({"META-INF/MANIFEST.MF": "Manifest-Version: 1.0\n"})
            if base == "mosaic-tty" and target in native_names:
                result[f"{parent}/{stem}-cinterop-mosaic.klib"] = archive({
                    "default/manifest": (f"native_targets={native_names[target]}\ncompiler_version=2.4.0\n"
                                         "builtins_platform=NATIVE\ninterop=true\n"),
                    "default/linkdata/module": b"offline cinterop metadata model"})
            component = {"group": spec["group"], "module": base, "version": version}
            if target != "root":
                component["url"] = f"../../{base}/{version}/{base}-{version}.module"
            variants = []
            for role in roles(target):
                extension = "-sources.jar" if role == "sources" else "-metadata.jar" if role == "metadata" else ext
                filenames = [stem + extension]
                if base == "mosaic-tty" and target in native_names and role == "api":
                    filenames.append(stem + "-cinterop-mosaic.klib")
                entries = []
                for filename in filenames:
                    payload = result[parent + "/" + filename]
                    entries.append({"name": filename, "url": filename,
                                    "size": len(payload), "sha256": digest(payload)})
                variant = {"name": role, "attributes": attributes(target, role), "files": entries}
                if role != "sources":
                    deps = api + (implementation if target not in ("jvm", "js") or role == "runtime" else [])
                    variant["dependencies"] = [{"group": spec["group"], "module": d,
                                               "version": {"requires": version}} for d in deps]
                variants.append(variant)
            if target == "root":
                variants += [{"name": t if role == "api" else t + role,
                              "attributes": attributes(t, role), "available-at": {
                    "group": spec["group"], "module": f"{base}-{t.lower()}", "version": version,
                    "url": f"../../{base}-{t.lower()}/{version}/{base}-{t.lower()}-{version}.module",
                }} for t in target_set(spec) for role in roles(t)]
            result[f"{parent}/{stem}.module"] = encoded({
                "formatVersion": "1.1", "component": component, "variants": variants,
            })
    for name, content in result.items():
        path = repo / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(content)
    return result


class ContractTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.repo = self.root / "repo"
        self.spec = FORKS["lucene"]
        self.fork = "lucene"
        self.version = "10.2.0-alpha14-kodex." + "a" * 12
        self.raw = fixture(self.repo, self.spec, self.version)

    def tearDown(self):
        self.temp.cleanup()

    def root_module(self):
        return next(self.repo.rglob("lucene-kmp-core-" + self.version + ".module"))

    def test_real_root_tooling_attachment_is_preserved_not_a_target_advertisement(self):
        root = self.root_module().with_name(
            "lucene-kmp-core-" + self.version + "-kotlin-tooling-metadata.json")
        tooling = {
            "schemaVersion": "1.1.0", "buildSystem": "Gradle",
            "buildPlugin": "org.jetbrains.kotlin.gradle.plugin.KotlinMultiplatformPluginWrapper",
            "buildPluginVersion": "2.4.0", "projectSettings": {"isHmppEnabled": True},
            # Original producer model can contain unexported targets; consumer variants
            # are governed by .module, not by mutating this truthful compiler attachment.
            "projectTargets": [{"target": "KotlinNativeTarget", "platformType": "native"}],
        }
        root.write_bytes(encoded(tooling))
        self.assertIn(root.relative_to(self.repo).as_posix(),
                      validate(self.repo, self.spec, self.version))
        tooling["buildSystem"] = "invented"
        root.write_bytes(encoded(tooling))
        with self.assertRaisesRegex(ValueError, "Invalid Kotlin tooling metadata"):
            validate(self.repo, self.spec, self.version)

    def test_tooling_attachment_is_not_allowed_on_a_platform_coordinate(self):
        module = next(self.repo.rglob("lucene-kmp-core-jvm-" + self.version + ".module"))
        module.with_name("lucene-kmp-core-jvm-" + self.version +
                         "-kotlin-tooling-metadata.json").write_bytes(b"{}")
        with self.assertRaisesRegex(ValueError, "Unexpected publication attachment"):
            validate(self.repo, self.spec, self.version)

    def test_complete_fixture(self):
        self.assertEqual(validate(self.repo, self.spec, self.version), self.raw)

    def test_native_logical_name_may_differ_from_url(self):
        module = next(self.repo.rglob("lucene-kmp-core-linuxx64-*.module"))
        doc = json.loads(module.read_bytes())
        doc["variants"][0]["files"][0]["name"] = "lucene-kmp-core-linuxX64Main-fixture.klib"
        module.write_bytes(encoded(doc))
        validate(self.repo, self.spec, self.version)

    def test_missing_payload(self):
        next(self.repo.rglob("*.klib")).unlink()
        with self.assertRaises(ValueError):
            validate(self.repo, self.spec, self.version)

    def test_corrupt_payload(self):
        next(self.repo.rglob("*.klib")).write_bytes(b"broken")
        with self.assertRaises(ValueError):
            validate(self.repo, self.spec, self.version)

    def test_wrong_fork_dependency(self):
        module = self.root_module()
        doc = json.loads(module.read_bytes())
        doc["variants"][0]["dependencies"] = [
            {"group": self.spec["group"], "module": "lucene-kmp-core", "version": {"requires": "upstream"}}]
        module.write_bytes(encoded(doc))
        with self.assertRaises(ValueError):
            validate(self.repo, self.spec, self.version)

    def test_metadata_reference_escape(self):
        module = self.root_module()
        doc = json.loads(module.read_bytes())
        doc["variants"][0]["files"][0]["url"] = "../../../../../../outside.jar"
        module.write_bytes(encoded(doc))
        with self.assertRaises(ValueError):
            validate(self.repo, self.spec, self.version)

    def test_unexpected_attachment(self):
        (self.root_module().parent / "unexpected.xml").write_bytes(b"x")
        with self.assertRaises(ValueError):
            validate(self.repo, self.spec, self.version)

    def test_symlink_rejected(self):
        try:
            (self.repo / "unsafe-link").symlink_to(self.root)
        except OSError:
            self.skipTest("Fixture environment cannot create symlinks")
        with self.assertRaises(ValueError):
            files(self.repo)

    def test_path_rejection(self):
        for name in ("../x", "/x", r"a\b", "a//b", "https://evil", "C:/x"):
            with self.subTest(name=name), self.assertRaises(ValueError):
                safe_name(name)

    def test_trusted_source_document_symlink(self):
        out = io.BytesIO()
        with tarfile.open(fileobj=out, mode="w") as tar:
            file_info = tarfile.TarInfo("AGENTS.md")
            content = b"source document fixture\n"
            file_info.size = len(content)
            tar.addfile(file_info, io.BytesIO(content))
            link = tarfile.TarInfo("CLAUDE.md")
            link.type = tarfile.SYMTYPE
            link.linkname = "AGENTS.md"
            tar.addfile(link)
        source = self.root / "archive-source"
        source.mkdir()
        extract_source(out.getvalue(), source)
        self.assertEqual((source / "CLAUDE.md").read_bytes(), content)

    def test_source_symlink_escape(self):
        out = io.BytesIO()
        with tarfile.open(fileobj=out, mode="w") as tar:
            link = tarfile.TarInfo("escape")
            link.type = tarfile.SYMTYPE
            link.linkname = "../../outside"
            tar.addfile(link)
        source = self.root / "archive-source"
        source.mkdir()
        with self.assertRaises(ValueError):
            extract_source(out.getvalue(), source)

    def test_prune_only_excluded_root_variants(self):
        doc = json.loads(self.root_module().read_bytes())
        doc["createdBy"] = {"gradle": {"version": "9.5.1", "buildId": "nondeterministic"}}
        doc["variants"].append({"name": "js", "available-at": {
            "group": self.spec["group"], "module": "lucene-kmp-core-js", "version": self.version,
            "url": "../../unused.module"}})
        normalized = normalize_module(encoded(doc), self.spec, self.version, True)
        self.assertEqual(next(iter(json.loads(normalized))), "formatVersion")
        self.assertEqual(len(json.loads(normalized)["variants"]), len(doc["variants"]) - 1)
        self.assertNotIn("buildId", json.loads(normalized)["createdBy"]["gradle"])

    def test_wrong_native_target(self):
        module = next(self.repo.rglob("lucene-kmp-core-linuxx64-*.module"))
        doc = json.loads(module.read_bytes())
        doc["variants"][0]["attributes"]["org.jetbrains.kotlin.native.target"] = "mingw_x64"
        module.write_bytes(encoded(doc))
        with self.assertRaises(ValueError):
            validate(self.repo, self.spec, self.version)

    def test_gradle_streaming_metadata_rejects_sorted_format_version(self):
        module = self.root_module()
        doc = json.loads(module.read_bytes())
        module.write_bytes((json.dumps(doc, sort_keys=True, indent=2) + "\n").encode())
        with self.assertRaisesRegex(ValueError, "formatVersion first"):
            validate(self.repo, self.spec, self.version)

    def test_wrong_native_compiler(self):
        with self.assertRaises(ValueError):
            validate(self.repo, self.spec, self.version, compiler_version="2.3.21")

    def test_zip_traversal_with_otherwise_valid_checksum(self):
        payload = self.root_module().with_suffix(".jar")
        value = archive({"../outside.txt": "unsafe fixture"})
        payload.write_bytes(value)
        module = self.root_module()
        doc = json.loads(module.read_bytes())
        entry = doc["variants"][0]["files"][0]
        entry["size"] = len(value)
        entry["sha256"] = digest(value)
        module.write_bytes(encoded(doc))
        with self.assertRaises(ValueError):
            validate(self.repo, self.spec, self.version)

    def stages(self):
        identity = {"fork": self.fork, "repository": self.spec["repository"],
                    "branch": "kodex-submodule", "commit": "a" * 40, "tree": "b" * 40,
                    "upstreamVersion": self.version.split("-kodex.")[0], "version": self.version,
                    "targetClosure": target_closure(self.spec)}
        stages = self.root / "stages"
        for host in HOSTS:
            targets = host_targets(self.spec, host)
            directory = stages / host
            selected = {}
            toolchain = {"gradle": "9.5.1", "kgp": "2.4.0",
                         "sourceInputs": {"fixture": "sha"}}
            if self.fork == "mcp":
                toolchain["nativeCompiler"] = "2.4.0"
                toolchain["compilerArchives"] = {"native": {"version": "2.4.0", "sha256": "c" * 64}}
                if host == "mac":
                    toolchain["jsCompiler"] = "2.4.0"
                    toolchain["compilerArchives"]["js"] = {"version": "2.4.0", "sha256": "d" * 64}
            for name, data in self.raw.items():
                artifact = Path(name).parts[-3]
                if artifact in self.spec["modules"]:
                    target = "root"
                else:
                    target = next(t for t in target_set(self.spec) if artifact.endswith("-" + t.lower()))
                if target in targets:
                    dest = directory / "repo" / name
                    dest.parent.mkdir(parents=True, exist_ok=True)
                    dest.write_bytes(data)
                    selected[name] = {"sha256": digest(data), "size": len(data)}
            write_json(directory / "toolchain.json", toolchain)
            write_json(directory / "stage.json", {
                "identity": identity, "host": host, "targets": targets,
                "recipe": recipe(self.fork), "toolchain": toolchain,
                "tasks": task_set(self.fork, host), "files": selected,
            })
        return stages, digest(encoded(identity))

    def test_merge_and_manifest(self):
        stages, identity_sha = self.stages()
        output = self.root / "bundle"
        merge(SimpleNamespace(input=stages, output=output, fork="lucene",
                              identity_sha256=identity_sha))
        doc, raw = verify_bundle(output, "lucene", identity_sha)
        self.assertEqual(raw, self.raw)
        self.assertEqual(doc["identity"]["commit"], "a" * 40)
        expected, marker = upload_set(output, "lucene", identity_sha)
        self.assertIn(marker, expected)
        self.assertNotIn("maven-metadata.xml", "\n".join(expected))
        receipts = self.root / "receipts"
        for host, target in {"linux": "linuxX64", "mac": "macosArm64", "windows": "mingwX64"}.items():
            tasks = ["verifyForkJvm", "runDebugExecutable" + target[0].upper() + target[1:]]
            if host == "linux":
                tasks += ["compileKotlinLinuxArm64"]
            write_json(receipts / f"{host}.json", {
                "schema": 1, "fork": "lucene", "host": host, "tasks": tasks,
                "manifestSha256": digest((output / "manifest.json").read_bytes()),
                "identitySha256": identity_sha, "runtime": ["jvm", target],
                "compileOnly": ["linuxArm64"] if host == "linux" else [],
            })
        verify_receipts(receipts, "lucene", output, identity_sha)
        (receipts / "mac.json").unlink()
        with self.assertRaises(ValueError):
            verify_receipts(receipts, "lucene", output, identity_sha)

    def test_merge_rejects_cross_host_files(self):
        stages, identity_sha = self.stages()
        doc_path = stages / "windows" / "stage.json"
        doc = json.loads(doc_path.read_bytes())
        doc["targets"] = ["root"]
        doc_path.write_bytes(encoded(doc))
        with self.assertRaises(ValueError):
            merge(SimpleNamespace(input=stages, output=self.root / "bundle", fork="lucene",
                                  identity_sha256=identity_sha))

    def test_merge_rejects_mixed_source(self):
        stages, identity_sha = self.stages()
        doc_path = stages / "windows" / "stage.json"
        doc = json.loads(doc_path.read_bytes())
        doc["identity"]["commit"] = "c" * 40
        doc_path.write_bytes(encoded(doc))
        with self.assertRaises(ValueError):
            merge(SimpleNamespace(input=stages, output=self.root / "bundle", fork="lucene",
                                  identity_sha256=identity_sha))


class McpJsTests(unittest.TestCase):
    # Reuse only the offline stage fixture helper, not Lucene's assertions.
    stages = ContractTests.stages

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.repo = self.root / "repo"
        self.fork = "mcp"
        self.spec = FORKS[self.fork]
        self.version = "0.14.0-kodex." + "a" * 12
        self.raw = fixture(self.repo, self.spec, self.version)

    def tearDown(self):
        self.temp.cleanup()

    def module(self, artifact):
        parent = coordinate(self.spec, artifact, self.version)
        return self.repo / parent / f"{artifact}-{self.version}.module"

    def replace_payload(self, artifact, extension, content):
        module = self.module(artifact)
        payload = module.with_name(f"{artifact}-{self.version}{extension}")
        payload.write_bytes(content)
        doc = json.loads(module.read_bytes())
        for variant in doc["variants"]:
            for entry in variant.get("files", []):
                if entry["url"] == payload.name:
                    entry.update(size=len(content), sha256=digest(content))
        module.write_bytes(encoded(doc))

    def bundle(self):
        stages, guarded = self.stages()
        output = self.root / "bundle"
        merge(SimpleNamespace(input=stages, output=output, fork=self.fork,
                              identity_sha256=guarded))
        return output, guarded

    def test_complete_sdk_js_payload_closure(self):
        self.assertEqual(validate(self.repo, self.spec, self.version,
                                  compiler_version="2.4.0", js_compiler_version="2.4.0"), self.raw)
        for base in self.spec["modules"]:
            doc = json.loads(self.module(base).read_bytes())
            self.assertIn(base + "-js", {v["available-at"]["module"] for v in doc["variants"]
                                         if "available-at" in v})
        self.assertEqual(set(target_set(self.spec)), set(TARGETS) | {"js"})
        self.assertEqual(host_targets(self.spec, "mac"), ("root", "jvm", "macosArm64", "js"))

    def test_normalization_keeps_js_not_wasm_or_ios(self):
        for base in self.spec["modules"]:
            doc = json.loads(self.module(base).read_bytes())
            for suffix in ("wasm-js", "iosarm64"):
                doc["variants"].append({"name": suffix, "available-at": {
                    "group": self.spec["group"], "version": self.version, "module": base + "-" + suffix,
                    "url": "../../not-published.module"}})
            doc = json.loads(normalize_module(encoded(doc), self.spec, self.version, True))
            links = {v["available-at"]["module"] for v in doc["variants"] if "available-at" in v}
            self.assertEqual(links, {base + "-" + t.lower() for t in target_set(self.spec)})

    def test_each_module_requires_js_klib_and_sources(self):
        for base in self.spec["modules"]:
            for extension in (".klib", "-sources.jar"):
                payload = self.module(base + "-js").with_name(f"{base}-js-{self.version}{extension}")
                original = payload.read_bytes()
                payload.unlink()
                with self.subTest(base=base, extension=extension), self.assertRaises(ValueError):
                    validate(self.repo, self.spec, self.version)
                payload.write_bytes(original)

    def test_missing_root_js_redirect_and_wrong_attributes(self):
        module = self.module("kotlin-sdk-core")
        original = module.read_bytes()
        doc = json.loads(original)
        doc["variants"] = [v for v in doc["variants"] if v["name"] != "js"]
        module.write_bytes(encoded(doc))
        with self.assertRaisesRegex(ValueError, "target closure"):
            validate(self.repo, self.spec, self.version)
        doc = json.loads(original)
        next(v for v in doc["variants"] if v["name"] == "js")["attributes"] = {
            "org.jetbrains.kotlin.platform.type": "wasm"}
        module.write_bytes(encoded(doc))
        with self.assertRaisesRegex(ValueError, "root JS redirect"):
            validate(self.repo, self.spec, self.version)

    def test_native_or_manifest_only_archive_cannot_impersonate_js(self):
        artifact = "kotlin-sdk-core-js"
        for manifest in ("builtins_platform=NATIVE\nnative_targets=linux_x64\n",
                         "builtins_platform=JS\nnative_targets=linux_x64\n",
                         "builtins_platform=WASM\n"):
            self.replace_payload(artifact, ".klib", archive({"default/manifest": manifest}))
            with self.subTest(manifest=manifest), self.assertRaisesRegex(ValueError, "JS platform"):
                validate(self.repo, self.spec, self.version)
        self.replace_payload(artifact, ".klib", archive({
            "default/manifest": "builtins_platform=JS\ncompiler_version=2.4.0\n"}))
        with self.assertRaisesRegex(ValueError, "JS IR/metadata"):
            validate(self.repo, self.spec, self.version)

    def test_js_compiler_version_is_separate_from_native(self):
        for base in self.spec["modules"]:
            self.replace_payload(base + "-js", ".klib", archive({
                "default/manifest": "builtins_platform=JS\ncompiler_version=2.4.0-dev-8449\n",
                "default/ir/bodies.knb": b"offline IR fixture",
                "default/linkdata/module": b"offline metadata fixture"}))
        validate(self.repo, self.spec, self.version, compiler_version="2.4.0",
                 js_compiler_version="2.4.0-dev-8449")
        with self.assertRaisesRegex(ValueError, "recorded platform compiler"):
            validate(self.repo, self.spec, self.version, compiler_version="2.4.0",
                     js_compiler_version="2.4.0")

    def test_js_variant_and_source_payload_required(self):
        module = self.module("kotlin-sdk-core-js")
        original = module.read_bytes()
        doc = json.loads(original)
        doc["variants"][0]["attributes"]["org.jetbrains.kotlin.platform.type"] = "native"
        module.write_bytes(encoded(doc))
        with self.assertRaisesRegex(ValueError, "JS variant"):
            validate(self.repo, self.spec, self.version)
        module.write_bytes(original)
        self.replace_payload("kotlin-sdk-core-js", "-sources.jar", archive({"fake.txt": "not source"}))
        with self.assertRaisesRegex(ValueError, "JS Kotlin sources"):
            validate(self.repo, self.spec, self.version)

    def test_js_pom_and_module_core_dependencies_required_and_versioned(self):
        for artifact in ("kotlin-sdk-client-js", "kotlin-sdk-server-js"):
            module = self.module(artifact)
            pom = module.with_suffix(".pom")
            original = pom.read_bytes()
            pom.write_bytes(original.replace(b"kotlin-sdk-core-js", b"kotlin-sdk-core-jvm"))
            with self.subTest(artifact=artifact), self.assertRaisesRegex(ValueError, "non-JS target"):
                validate(self.repo, self.spec, self.version)
            pom.write_bytes(original.replace(
                f"<version>{self.version}</version><scope>".encode(), b"<version>upstream</version><scope>"))
            with self.assertRaisesRegex(ValueError, "POM fork dependency"):
                validate(self.repo, self.spec, self.version)
            pom.write_bytes(original.replace(b"<artifactId>kotlin-sdk-core-js</artifactId>",
                                             b"<artifactId>kotlin-sdk-client-js</artifactId>"))
            with self.assertRaisesRegex(ValueError, "Missing SDK JS core dependency"):
                validate(self.repo, self.spec, self.version)
            pom.write_bytes(original)
            doc = json.loads(module.read_bytes())
            saved = module.read_bytes()
            doc["variants"][0]["dependencies"] = []
            module.write_bytes(encoded(doc))
            with self.assertRaisesRegex(ValueError, "Missing SDK JS core dependency"):
                validate(self.repo, self.spec, self.version)
            module.write_bytes(saved)

    def test_merge_binds_js_authority_guard_and_old_bundle_rejection(self):
        output, guarded = self.bundle()
        doc, raw = verify_bundle(output, self.fork, guarded)
        self.assertEqual(raw, self.raw)
        self.assertIn("js", doc["identity"]["targetClosure"]["hosts"]["mac"])
        old_targets = list(doc["targets"])
        doc["targets"].remove("js")
        write_json(output / "manifest.json", doc)
        with self.assertRaisesRegex(ValueError, "manifest closure"):
            verify_bundle(output, self.fork)
        doc["targets"] = old_targets
        doc["identity"]["targetClosure"]["targets"].remove("js")
        self.assertNotEqual(digest(encoded(doc["identity"])), guarded)
        write_json(output / "manifest.json", doc)
        with self.assertRaisesRegex(ValueError, "target closure"):
            verify_bundle(output, self.fork)

    def test_linux_cannot_contribute_js_and_task_drift_rejected(self):
        stages, guarded = self.stages()
        stage = stages / "linux" / "stage.json"
        doc = json.loads(stage.read_bytes())
        doc["targets"].append("js")
        stage.write_bytes(encoded(doc))
        with self.assertRaisesRegex(ValueError, "stage host"):
            merge(SimpleNamespace(input=stages, output=self.root / "bad-bundle", fork=self.fork,
                                  identity_sha256=guarded))
        doc["targets"].remove("js")
        stage.write_bytes(encoded(doc))
        stage = stages / "mac" / "stage.json"
        doc = json.loads(stage.read_bytes())
        doc["tasks"] = [t for t in doc["tasks"] if ":publishJs" not in t]
        stage.write_bytes(encoded(doc))
        with self.assertRaisesRegex(ValueError, "host/task closure"):
            merge(SimpleNamespace(input=stages, output=self.root / "other-bundle", fork=self.fork,
                                  identity_sha256=guarded))

    def test_js_smoke_real_source_and_receipt_cannot_skip_compile(self):
        output, guarded = self.bundle()
        consumer = self.root / "consumer"
        receipts = self.root / "receipts"
        with patch("smoke.platform.system", return_value="Linux"), \
                patch("smoke.isolated_environment"), patch("smoke.shutil.which", return_value="/provided/node"), \
                patch("smoke.run", return_value="v22.14.0\n") as runner, \
                patch.dict("os.environ", {"JAVA_HOME": "/fixture/java"}):
            smoke(SimpleNamespace(root=self.root, bundle=output, output=consumer, fork=self.fork,
                                  host="linux", receipt=receipts / "linux.json", identity_sha256=guarded))
        source = (consumer / "src/jsMain/kotlin/Probe.kt").read_text()
        self.assertIn("StdioClientTransport(input = source, output = sink, error = error)", source)
        self.assertIn("stdio.send(JSONRPCNotification(", source)
        self.assertIn("StreamableHttpClientTransport(client = http", source)
        self.assertIn("HttpClient { install(SSE) }", source)
        self.assertIn(self.version, source)
        self.assertNotIn("runBlocking", source)
        self.assertFalse((consumer / "src/commonMain/kotlin/Probe.kt").exists())
        for target in ("jvm", "linuxX64", "linuxArm64"):
            self.assertIn("runBlocking", (consumer / f"src/{target}Main/kotlin/Probe.kt").read_text())
        build = (consumer / "build.gradle").read_text()
        self.assertIn("js { nodejs(); binaries.executable() }", build)
        self.assertIn("NodeJsEnvSpec", build)
        self.assertIn("download.set(false)", build)
        self.assertIn("ktor-client-js:3.5.1", build)
        self.assertTrue(any(call.args[0][-5:] == smoke_gates("mcp", "linux")["tasks"]
                            for call in runner.call_args_list))
        for host in ("mac", "windows"):
            write_json(receipts / f"{host}.json", {
                "schema": 1, "fork": self.fork, "host": host, **smoke_gates(self.fork, host),
                "manifestSha256": digest((output / "manifest.json").read_bytes()),
                "identitySha256": guarded})
        verify_receipts(receipts, self.fork, output, guarded)
        receipt = json.loads((receipts / "linux.json").read_bytes())
        receipt["tasks"].remove("compileKotlinJs")
        write_json(receipts / "linux.json", receipt)
        with self.assertRaisesRegex(ValueError, "receipt"):
            verify_receipts(receipts, self.fork, output, guarded)

    def test_node_minimum_cannot_silently_skip_js_gate(self):
        with patch("smoke.shutil.which", return_value=None):
            with self.assertRaisesRegex(ValueError, "requires provided Node"):
                node_environment()
        with patch("smoke.shutil.which", return_value="/fixture/node"), \
                patch("smoke.run", return_value="v20.19.0\n"):
            with self.assertRaisesRegex(ValueError, "requires Node"):
                node_environment()

    def test_compiler_evidence_uses_actual_version_resource_not_kgp_assumption(self):
        compiler = self.root / "compiler.jar"
        compiler.write_bytes(archive({"META-INF/compiler.version": "2.4.0-dev-8449\n"}))
        evidence = compiler_archive_evidence([compiler], "2.4.0")
        self.assertEqual(evidence, {"version": "2.4.0-dev-8449", "sha256": digest(compiler.read_bytes())})
        with self.assertRaisesRegex(ValueError, "pinned KGP"):
            compiler_archive_evidence([compiler], "2.3.21")
        with self.assertRaisesRegex(ValueError, "Missing/mixed"):
            compiler_archive_evidence([], "2.4.0")

    def test_compiler_private_cache_locations_and_host_split(self):
        native = self.root / "konan/kotlin-native-prebuilt-macos-aarch64-2.4.0/konan/lib"
        native.mkdir(parents=True)
        (native / "kotlin-native-compiler-embeddable.jar").write_bytes(
            archive({"META-INF/compiler.version": "2.4.0"}))
        js = self.root / "gradle/caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-compiler-embeddable/2.4.0/hash"
        js.mkdir(parents=True)
        (js / "kotlin-compiler-embeddable-2.4.0.jar").write_bytes(
            archive({"META-INF/compiler.version": "2.4.0-dev-8449"}))
        with patch.dict("os.environ", {"KONAN_DATA_DIR": str(self.root / "konan"),
                                      "GRADLE_USER_HOME": str(self.root / "gradle")}):
            self.assertEqual(set(mcp_compiler_evidence("2.4.0", "mac")), {"native", "js"})
            self.assertEqual(set(mcp_compiler_evidence("2.4.0", "linux")), {"native"})

    def test_js_manifest_java_properties_line_endings(self):
        self.replace_payload("kotlin-sdk-core-js", ".klib", archive({
            "default/manifest": "builtins_platform=JS\r\ncompiler_version=2.4.0\r\n",
            "default/ir/bodies.knb": b"offline IR fixture",
            "default/linkdata/module": b"offline metadata fixture"}))
        validate(self.repo, self.spec, self.version, compiler_version="2.4.0", js_compiler_version="2.4.0")

    def test_sdk_native_platform_cannot_be_js_with_native_target(self):
        self.replace_payload("kotlin-sdk-core-linuxx64", ".klib", archive({
            "default/manifest": "builtins_platform=JS\nnative_targets=linux_x64\ncompiler_version=2.4.0\n"}))
        with self.assertRaisesRegex(ValueError, "Native platform"):
            validate(self.repo, self.spec, self.version)

    def test_bundle_requires_platform_compiler_evidence(self):
        output, guarded = self.bundle()
        manifest = output / "manifest.json"
        original = manifest.read_bytes()
        doc = json.loads(original)
        doc["hosts"]["mac"]["toolchain"]["compilerArchives"].pop("js")
        manifest.write_bytes(encoded(doc))
        with self.assertRaisesRegex(ValueError, "platform compiler evidence"):
            verify_bundle(output, self.fork, guarded)
        doc = json.loads(original)
        doc["hosts"]["mac"]["toolchain"]["jsCompiler"] = "2.4.0-dev-8449"
        manifest.write_bytes(encoded(doc))
        with self.assertRaisesRegex(ValueError, "JS compiler evidence mismatch"):
            verify_bundle(output, self.fork, guarded)


class PublicationShapeTests(unittest.TestCase):
    # Same offline model helpers, no runtime/compiler/pinned-source-byte claim.
    module = McpJsTests.module
    replace_payload = McpJsTests.replace_payload

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.repo = self.root / "repo"
        self.spec = FORKS["mosaic"]
        self.version = "0.19.0-SNAPSHOT-kodex." + "a" * 12
        fixture(self.repo, self.spec, self.version)

    def tearDown(self):
        self.temp.cleanup()

    def test_tty_terminal_without_cinterop_or_bitcode_is_valid(self):
        validate(self.repo, self.spec, self.version)
        for target in TARGETS[1:]:
            module = self.module("mosaic-tty-terminal-" + target.lower())
            self.assertFalse(list(module.parent.glob("*cinterop*")))
            with zipfile.ZipFile(module.with_suffix(".klib")) as payload:
                self.assertFalse(any(n.endswith("/native/mosaic.bc") for n in payload.namelist()))

    def test_tty_terminal_cinterop_attachment_is_rejected(self):
        module = self.module("mosaic-tty-terminal-linuxx64")
        module.with_name(module.stem + "-cinterop-mosaic.klib").write_bytes(
            archive({"default/manifest": "offline misplaced attachment"}))
        with self.assertRaisesRegex(ValueError, "Unexpected publication attachment"):
            validate(self.repo, self.spec, self.version)

    def test_tty_still_requires_own_cinterop_and_bitcode(self):
        artifact = "mosaic-tty-linuxx64"
        module = self.module(artifact)
        original = module.read_bytes()
        attachment = module.with_name(module.stem + "-cinterop-mosaic.klib")
        saved = attachment.read_bytes()
        attachment.unlink()
        doc = json.loads(original)
        doc["variants"][0]["files"] = [e for e in doc["variants"][0]["files"]
                                       if "cinterop" not in e["url"]]
        module.write_bytes(encoded(doc))
        with self.assertRaisesRegex(ValueError, "Missing Mosaic Native cinterop"):
            validate(self.repo, self.spec, self.version)
        attachment.write_bytes(saved)
        module.write_bytes(original)
        self.replace_payload(artifact, ".klib", archive({
            "default/manifest": "native_targets=linux_x64\ncompiler_version=2.4.0\n"}))
        with self.assertRaisesRegex(ValueError, "Missing Mosaic Native C bitcode"):
            validate(self.repo, self.spec, self.version)

    def test_root_native_redirect_attributes_and_roles(self):
        module = self.module("mosaic-runtime")
        original = module.read_bytes()
        for field, value in (("org.jetbrains.kotlin.native.target", "mingw_x64"),
                             ("org.jetbrains.kotlin.platform.type", "jvm"),
                             ("org.gradle.jvm.environment", "standard-jvm"),
                             ("org.gradle.usage", "java-api"),
                             ("org.gradle.category", "documentation")):
            doc = json.loads(original)
            variant = next(v for v in doc["variants"] if v["name"] == "linuxX64")
            variant["attributes"][field] = value
            module.write_bytes(encoded(doc))
            with self.subTest(field=field), self.assertRaises(ValueError):
                validate(self.repo, self.spec, self.version)
        doc = json.loads(original)
        doc["variants"] = [v for v in doc["variants"] if v["name"] != "jvmruntime"]
        module.write_bytes(encoded(doc))
        with self.assertRaisesRegex(ValueError, "target closure"):
            validate(self.repo, self.spec, self.version)

    def test_essential_edges_required_in_root_and_target_not_source_variants(self):
        for artifact in ("mosaic-runtime", "mosaic-runtime-jvm", "mosaic-runtime-linuxx64"):
            module = self.module(artifact)
            original = module.read_bytes()
            doc = json.loads(original)
            variant = next(v for v in doc["variants"] if v["name"] ==
                           ("runtime" if artifact.endswith("-jvm") else "api"))
            variant["dependencies"] = [d for d in variant["dependencies"]
                                       if d["module"] != "mosaic-tty-terminal"]
            module.write_bytes(encoded(doc))
            with self.subTest(artifact=artifact), self.assertRaisesRegex(ValueError, "essential module"):
                validate(self.repo, self.spec, self.version)
            module.write_bytes(original)
            pom = module.with_suffix(".pom")
            saved = pom.read_bytes()
            pom.write_bytes(saved.replace(b"mosaic-tty-terminal", b"mosaic-testing"))
            with self.subTest(artifact=artifact), self.assertRaisesRegex(ValueError, "essential POM"):
                validate(self.repo, self.spec, self.version)
            pom.write_bytes(saved)
        # Model source variants deliberately have no dependencies.
        validate(self.repo, self.spec, self.version)

    def test_root_jar_needs_structure_and_common_metadata_not_fixture_text(self):
        artifact = "mosaic-runtime"
        payload = self.module(artifact).with_suffix(".jar")
        original = payload.read_bytes()
        self.replace_payload(artifact, ".jar", archive({"fixture.txt": "junk"}))
        with self.assertRaisesRegex(ValueError, "KMP project structure"):
            validate(self.repo, self.spec, self.version)
        with zipfile.ZipFile(io.BytesIO(original)) as zipped:
            entries = {n: zipped.read(n) for n in zipped.namelist() if not n.endswith(".knm")}
        self.replace_payload(artifact, ".jar", archive(entries))
        with self.assertRaisesRegex(ValueError, "common Kotlin metadata"):
            validate(self.repo, self.spec, self.version)
        with zipfile.ZipFile(io.BytesIO(original)) as zipped:
            entries = {n: zipped.read(n) for n in zipped.namelist()}
        path = "META-INF/kotlin-project-structure-metadata.json"
        structure = json.loads(entries[path])
        structure["projectStructure"]["variants"][0]["sourceSet"] = ["nonexistentMain"]
        entries[path] = encoded(structure)
        self.replace_payload(artifact, ".jar", archive(entries))
        with self.assertRaisesRegex(ValueError, "variant source sets"):
            validate(self.repo, self.spec, self.version)

    def test_root_and_native_source_jars_reject_junk_with_valid_hashes(self):
        for artifact in ("mosaic-terminal", "mosaic-terminal-linuxx64"):
            payload = self.module(artifact).with_name(f"{artifact}-{self.version}-sources.jar")
            original = payload.read_bytes()
            self.replace_payload(artifact, "-sources.jar", archive({"fixture.txt": "not source"}))
            with self.subTest(artifact=artifact), self.assertRaisesRegex(ValueError, "Kotlin sources"):
                validate(self.repo, self.spec, self.version)
            self.replace_payload(artifact, "-sources.jar", original)

    def test_exact_jni_loader_paths_ffm_classes_and_multi_release(self):
        artifact = "mosaic-tty-jvm"
        with zipfile.ZipFile(self.module(artifact).with_suffix(".jar")) as zipped:
            original = {n: zipped.read(n) for n in zipped.namelist()}
        for name in ("com/jakewharton/mosaic/tty/jni/amd64/mosaic.dll",
                     "META-INF/versions/22/com/jakewharton/mosaic/tty/Libmosaic.class",
                     "META-INF/MANIFEST.MF"):
            entries = dict(original)
            entries["wrong/" + name] = entries.pop(name)
            self.replace_payload(artifact, ".jar", archive(entries))
            with self.subTest(name=name), self.assertRaisesRegex(ValueError, "FFM/JNI"):
                validate(self.repo, self.spec, self.version)
        entries = dict(original)
        entries["META-INF/MANIFEST.MF"] = "Manifest-Version: 1.0\r\nMulti-Release: false\r\n"
        self.replace_payload(artifact, ".jar", archive(entries))
        with self.assertRaisesRegex(ValueError, "FFM/JNI"):
            validate(self.repo, self.spec, self.version)


class HTTPFixture:
    """Loopback-only HTTP Basic registry fixture with explicit fault injection."""
    def __init__(self):
        self.data = {}
        self.puts = []
        self.fail_path = None
        self.corrupt_path = None
        self.redirect = False
        self.automatic_checksums = False
        fixture_self = self
        authorization = "Basic " + base64.b64encode(b"fixture:nonsecret-fixture-token").decode()

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass

            def do_GET(self):
                if self.headers.get("Authorization") != authorization:
                    self.send_error(401)
                    return
                if fixture_self.redirect:
                    self.send_response(302)
                    self.send_header("Location", "http://127.0.0.1:1/must-not-follow")
                    self.end_headers()
                    return
                value = fixture_self.data.get(self.path.lstrip("/"))
                if value is None:
                    self.send_error(404)
                else:
                    self.send_response(200)
                    self.end_headers()
                    self.wfile.write(value)

            def do_PUT(self):
                if self.headers.get("Authorization") != authorization:
                    self.send_error(401)
                    return
                path = self.path.lstrip("/")
                if path == fixture_self.fail_path:
                    self.send_error(500)
                    return
                if path in fixture_self.data or self.headers.get("If-None-Match") != "*":
                    self.send_error(409)
                    return
                value = self.rfile.read(int(self.headers["Content-Length"]))
                if path == fixture_self.corrupt_path:
                    value += b"corrupt"
                fixture_self.data[path] = value
                if fixture_self.automatic_checksums and path.endswith(".jar"):
                    fixture_self.data[path + ".sha1"] = hashlib.sha1(value).hexdigest().encode()
                fixture_self.puts.append(path)
                self.send_response(201)
                self.end_headers()

        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)

    def __enter__(self):
        self.thread.start()
        self.client = MavenHTTP(f"http://127.0.0.1:{self.server.server_port}",
                                "fixture", "nonsecret-fixture-token")
        return self

    def __exit__(self, *args):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=5)


class PublisherTests(unittest.TestCase):
    def expected(self):
        return {"g/a/v/a-v.jar": b"payload", "g/a/v/a-v.pom": b"pom",
                "g/a/v/a-v.module": b"module", "g/a/v/a-v-kodex-manifest.json": b"manifest"}

    def test_basic_upload_remote_bytes_and_exact_duplicate(self):
        with HTTPFixture() as fixture_http:
            expected = self.expected()
            marker = "g/a/v/a-v-kodex-manifest.json"
            self.assertEqual(publish(fixture_http.client, expected, marker, lambda: None,
                                     wait=lambda _: None), "complete-remote-verified")
            self.assertEqual(fixture_http.puts[-1], marker)
            fixture_http.puts.clear()
            self.assertEqual(publish(fixture_http.client, expected, marker, lambda: None),
                             "complete-exact")
            self.assertEqual(fixture_http.puts, [])

    def test_partial_fails_without_overwrite(self):
        with HTTPFixture() as fixture_http:
            fixture_http.data["g/a/v/a-v.jar"] = b"payload"
            with self.assertRaisesRegex(RuntimeError, "PARTIAL OR DIFFERENT"):
                classify(fixture_http.client, self.expected())
            self.assertEqual(fixture_http.puts, [])

    def test_generated_checksum_skipped_only_when_exact(self):
        with HTTPFixture() as fixture_http:
            fixture_http.automatic_checksums = True
            expected = self.expected()
            expected["g/a/v/a-v.jar.sha1"] = hashlib.sha1(b"payload").hexdigest().encode()
            self.assertEqual(publish(fixture_http.client, expected, "g/a/v/a-v-kodex-manifest.json",
                                     lambda: None), "complete-remote-verified")
            self.assertNotIn("g/a/v/a-v.jar.sha1", fixture_http.puts)

    def test_different_complete_fails(self):
        with HTTPFixture() as fixture_http:
            fixture_http.data.update(self.expected())
            fixture_http.data["g/a/v/a-v.jar"] = b"old-user-content"
            with self.assertRaises(RuntimeError):
                publish(fixture_http.client, self.expected(), "g/a/v/a-v-kodex-manifest.json",
                        lambda: None)
            self.assertEqual(fixture_http.puts, [])

    def test_interruption_does_not_install_manifest_or_resume(self):
        with HTTPFixture() as fixture_http:
            fixture_http.fail_path = "g/a/v/a-v.module"
            with self.assertRaisesRegex(RuntimeError, "MAY BE PARTIAL"):
                publish(fixture_http.client, self.expected(), "g/a/v/a-v-kodex-manifest.json",
                        lambda: None)
            self.assertNotIn("g/a/v/a-v-kodex-manifest.json", fixture_http.data)
            previous = dict(fixture_http.data)
            with self.assertRaisesRegex(RuntimeError, "PARTIAL OR DIFFERENT"):
                publish(fixture_http.client, self.expected(), "g/a/v/a-v-kodex-manifest.json",
                        lambda: None)
            self.assertEqual(fixture_http.data, previous)

    def test_corrupt_remote_bytes_never_report_complete(self):
        with HTTPFixture() as fixture_http:
            fixture_http.corrupt_path = "g/a/v/a-v.jar"
            with self.assertRaisesRegex(RuntimeError, "MAY BE PARTIAL"):
                publish(fixture_http.client, self.expected(), "g/a/v/a-v-kodex-manifest.json",
                        lambda: None, wait=lambda _: None)

    def test_stale_main_fails_before_write(self):
        with HTTPFixture() as fixture_http:
            def stale():
                raise ValueError("stale HEAD fixture")
            with self.assertRaises(ValueError):
                publish(fixture_http.client, self.expected(), "g/a/v/a-v-kodex-manifest.json", stale)
            self.assertEqual(fixture_http.puts, [])

    def test_live_head_is_rechecked_before_completion(self):
        with HTTPFixture() as fixture_http, patch("publisher.time.monotonic", return_value=0):
            count = 0
            def changed_after_verification():
                nonlocal count
                count += 1
                if count == 3:
                    raise ValueError("stale final HEAD")
            with self.assertRaisesRegex(RuntimeError, "MAY BE PARTIAL"):
                publish(fixture_http.client, self.expected(), "g/a/v/a-v-kodex-manifest.json",
                        changed_after_verification)
            self.assertEqual(count, 3)

    def test_redirect_and_bad_auth_refused(self):
        with HTTPFixture() as fixture_http:
            fixture_http.redirect = True
            with self.assertRaisesRegex(RuntimeError, "HTTP 302"):
                fixture_http.client.get("g/a/v/file")
            fixture_http.redirect = False
            bad = MavenHTTP(f"http://127.0.0.1:{fixture_http.server.server_port}", "fixture", "wrong")
            with self.assertRaisesRegex(RuntimeError, "HTTP 401"):
                bad.get("g/a/v/file")

    def test_path_escape_fails_before_http(self):
        with HTTPFixture() as fixture_http:
            with self.assertRaises(ValueError):
                fixture_http.client.get("../outside")


class TaskTests(unittest.TestCase):
    def test_module_target_task_closure(self):
        expected_counts = {"mosaic": 36, "mcp": 21, "lucene": 6}
        for fork, count in expected_counts.items():
            tasks = sum((task_set(fork, host) for host in HOSTS), [])
            self.assertEqual(len(tasks), count)
            self.assertEqual(len(set(tasks)), count)
            self.assertTrue(all(t.endswith("PublicationToForkStagingRepository") for t in tasks))
            self.assertFalse(any(x in t for t in tasks for x in ("Wasm", "Ios", "MacosX64")))
            js = [t for t in tasks if ":publishJs" in t]
            self.assertEqual(js, [f":kotlin-sdk-{m}:publishJsPublicationToForkStagingRepository"
                                  for m in ("core", "client", "server")] if fork == "mcp" else [])

    def test_exact_host_authority_and_non_sdk_matrix_unchanged(self):
        for fork, spec in FORKS.items():
            for host in HOSTS:
                targets = {"linux": ("LinuxX64", "LinuxArm64"),
                           "mac": ("KotlinMultiplatform", "Jvm", "MacosArm64"),
                           "windows": ("MingwX64",)}[host]
                if fork == "mcp" and host == "mac":
                    targets += ("Js",)
                self.assertEqual(task_set(fork, host), [
                    f"{project}:publish{t}PublicationToForkStagingRepository"
                    for project in spec["modules"].values() for t in targets])
                expected = ["verifyForkJvm", {"linux": "runDebugExecutableLinuxX64",
                                              "mac": "runDebugExecutableMacosArm64",
                                              "windows": "runDebugExecutableMingwX64"}[host]]
                if fork == "mosaic":
                    expected += ["verifyForkJvmJni"]
                if host == "linux":
                    expected += ["compileKotlinLinuxArm64"]
                if fork == "mcp" and host == "linux":
                    expected += ["compileKotlinJs", "jsNodeDevelopmentRun"]
                self.assertEqual(smoke_gates(fork, host)["tasks"], expected)


class GuardTests(unittest.TestCase):
    def test_non_main_and_pr_refused_without_git(self):
        for ref, event in (("refs/heads/other", "workflow_dispatch"),
                           ("refs/heads/main", "pull_request")):
            with self.subTest(ref=ref, event=event), patch.dict("os.environ", {
                "GITHUB_REPOSITORY": "Stream29/Kodex", "GITHUB_REF": ref,
                "GITHUB_EVENT_NAME": event,
            }), patch("pipeline.git") as command:
                with self.assertRaises(ValueError):
                    guard(SimpleNamespace(root=Path("."), sha="a" * 40, fork="mosaic"))
                command.assert_not_called()

    def test_stale_main(self):
        with patch("pipeline.git", return_value="b" * 40 + "\trefs/heads/main"):
            with self.assertRaisesRegex(ValueError, "live main"):
                live_main("a" * 40)

    def test_literal_snapshot_version_and_public_ancestry_fetch(self):
        commit = "a" * 40
        def command(*args, cwd=None):
            if args[0] == "ls-tree":
                return f"160000 commit {commit}\tMosaic"
            if args[:2] == ("rev-parse", "HEAD"):
                return commit
            if args[0] in ("status", "fetch", "submodule"):
                return ""
            if args[0] == "-c":
                return "https://github.com/Stream29/mosaic.git"
            if args[0] == "show":
                return "VERSION_NAME=0.19.0-SNAPSHOT\n"
            if args[0] == "rev-parse":
                return "b" * 40
            raise AssertionError("Unexpected fixture Git call")
        with patch("pipeline.git", side_effect=command) as commands, patch("pipeline.run", return_value="") as runs:
            result = identity("mosaic", Path("/unused-fixture"))
        self.assertEqual(result["version"], "0.19.0-SNAPSHOT-kodex." + "a" * 12)
        self.assertEqual(result["targetClosure"], target_closure(FORKS["mosaic"]))
        self.assertTrue(any(call.args[:3] == ("fetch", "--no-tags", "https://github.com/Stream29/mosaic.git")
                            for call in commands.call_args_list))
        self.assertTrue(any("--is-ancestor" in call.args[0] for call in runs.call_args_list))

    def test_sdk_inputs_without_real_sdk_execution(self):
        with tempfile.TemporaryDirectory() as temp:
            sdk = Path(temp) / "android"
            manager = sdk / "cmdline-tools/latest/bin/sdkmanager"
            manager.parent.mkdir(parents=True)
            manager.write_text("fixture, never executed")
            inputs = ("platforms/android-36/android.jar", "platforms/android-36/source.properties",
                      "build-tools/36.0.0/source.properties")
            def install(args, **kwargs):
                self.assertIn("platforms;android-36", args)
                self.assertIn("build-tools;36.0.0", args)
                self.assertNotIn("--licenses", args)
                for name in inputs:
                    destination = sdk / name
                    destination.parent.mkdir(parents=True, exist_ok=True)
                    destination.write_bytes(b"fixture SDK input")
            with patch.dict("os.environ", {"ANDROID_HOME": str(sdk)}), \
                    patch("pipeline.run", side_effect=install) as installer:
                result = prepare_android("linux")
                installer.assert_called_once()
                installer.reset_mock()
                self.assertEqual(prepare_android("linux"), result)
                installer.assert_not_called()
                self.assertEqual(set(result["inputs"]), set(inputs))


class WorkflowTests(unittest.TestCase):
    def test_node_setup_is_sdk_linux_smoke_only(self):
        root = Path(__file__).resolve().parents[2]
        for fork in FORKS:
            text = (root / ".github/workflows" / f"fork-packages-{fork}.yml").read_text()
            if fork == "mcp":
                self.assertEqual(text.count("actions/setup-node@v4"), 1)
                smoke_job = text.split("\n  smoke:\n")[1].split("\n  publish:\n")[0]
                self.assertIn("if: matrix.host == 'linux'", smoke_job)
                self.assertIn('node-version: "22.14.0"', smoke_job)
            else:
                self.assertNotIn("actions/setup-node", text)

    def test_permission_trigger_and_single_writer_boundaries(self):
        root = Path(__file__).resolve().parents[2]
        for fork, spec in FORKS.items():
            text = (root / ".github/workflows" / f"fork-packages-{fork}.yml").read_text()
            with self.subTest(fork=fork):
                self.assertIn(f"paths: [{spec['path']}]", text)
                self.assertIn("branches: [main]", text)
                self.assertIn("github.ref == 'refs/heads/main'", text)
                self.assertIn("inputs.reviewed", text)
                self.assertEqual(text.count("packages: write"), 1)
                self.assertNotIn("pull_request_target", text)
                self.assertNotIn("workflow_run:", text)
                self.assertNotIn("cancel-in-progress: true", text)
                self.assertNotIn("secrets.", text)
                publish_job = text.split("\n  publish:\n")[1]
                self.assertIn("needs: [gate, merge, smoke]", publish_job)
                self.assertNotIn("pipeline.py build", publish_job)
                self.assertNotIn("smoke.py", publish_job)
                self.assertNotIn("submodules:", publish_job)
                self.assertIn("publisher.py", publish_job)
                self.assertEqual(text.count("persist-credentials: false"), text.count("actions/checkout@"))
                self.assertEqual(text.count("ref: ${{ github.sha }}"), text.count("actions/checkout@"))


if __name__ == "__main__":
    unittest.main()
