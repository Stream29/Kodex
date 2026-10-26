#!/usr/bin/env python3
"""Validate and publish the single pinned Compose Wasm leaf through main CI."""
import argparse
import hashlib
import json
import os
import re
import subprocess
from pathlib import Path
from xml.etree import ElementTree as ET

from contract import digest, encoded, require
from pipeline import live_main, require_trusted_event
from publisher import ENDPOINT, MavenHTTP, publish

SOURCE = "dependency-fix/compose-ui"
REPOSITORY = "Stream29/compose-multiplatform-core"
GROUP_PATH = "org/jetbrains/compose/ui/ui-wasm-js"
REGRESSIONS = {
    "clickableControlsKeepTheirExplicitRoleAndExportCurrentCheckedOrSelectedState",
    "reusedLayoutCannotRetargetAnOldDomActionToANewSemanticsIdentity",
    "nestedOwnersRestoreAndLastRemovalClearsTheMirror",
    "retainedDomUsesCurrentActionAndDisabledAdmissionRatherThanCapturedConfig",
}


def git(root, *arguments):
    return subprocess.check_output(["git", "-C", str(root), *arguments], text=True).strip()


def identity(root):
    source = root / SOURCE
    commit = git(source, "rev-parse", "HEAD")
    require(re.fullmatch(r"[0-9a-f]{40}", commit), "Invalid producer commit")
    pin = git(root, "ls-tree", "HEAD", "--", SOURCE).split()
    require(len(pin) == 4 and pin[:2] == ["160000", "commit"] and pin[2] == commit,
            "Producer differs from the reviewed product gitlink")
    require(not git(source, "status", "--porcelain", "--untracked-files=normal"),
            "Producer must be clean")
    require(git(source, "remote", "get-url", "origin") in {
        f"https://github.com/{REPOSITORY}.git", f"git@github.com:{REPOSITORY}.git",
    }, "Unexpected producer repository")
    return {
        "sourcePath": SOURCE, "repository": REPOSITORY,
        "commit": commit, "tree": git(source, "rev-parse", "HEAD^{tree}"),
        "version": "1.11.1-kodex." + commit[:12],
    }


def verify_regressions(report_directory):
    found = set()
    reports = list(report_directory.glob("*.xml"))
    require(reports, "Missing binary-only browser regression reports")
    for path in reports:
        report = ET.parse(path).getroot()
        for test in report.iter("testcase"):
            require(not any(test.find(name) is not None for name in ("failure", "error", "skipped")),
                    "Failed or skipped binary regression")
            name = test.attrib.get("name", "").split("[", 1)[0].strip()
            if name in REGRESSIONS:
                require(name not in found, "Duplicate binary regression")
                found.add(name)
    require(found == REGRESSIONS, "All four strict binary regressions must execute")
    return {p.name: digest(p.read_bytes()) for p in sorted(reports)}


def payload(bundle, wanted_identity):
    manifest = json.loads((bundle / "bundle.json").read_text())
    require(manifest["identity"] == wanted_identity, "Bundle source identity mismatch")
    version = wanted_identity["version"]
    base = f"{GROUP_PATH}/{version}"
    stem = f"ui-wasm-js-{version}"
    names = {f"{stem}.{suffix}" for suffix in ("pom", "module", "klib")} | {
        f"{stem}-sources.jar", f"{stem}-kotlin_resources.kotlin_resources.zip",
    }
    expected_names = {f"{base}/{name}" for name in names}
    require(set(manifest["files"]) == expected_names, "Unexpected Wasm publication closure")
    actual_names = {p.relative_to(bundle / "repo").as_posix()
                    for p in (bundle / "repo").rglob("*") if p.is_file()}
    require(actual_names == expected_names, "Unexpected bundle files")
    require(not any(p.is_symlink() for p in (bundle / "repo").rglob("*")),
            "Publication cannot contain symlinks")
    raw = {}
    for path, metadata in manifest["files"].items():
        data = (bundle / "repo" / path).read_bytes()
        require(metadata == {"size": len(data), "sha256": digest(data)}, "Bundle digest mismatch")
        raw[path] = data
    module = json.loads(raw[f"{base}/{stem}.module"])
    require(module["component"]["group"] == "org.jetbrains.compose.ui" and
            module["component"]["module"] == "ui-wasm-js" and
            module["component"]["version"] == version and
            "url" not in module["component"], "Non-self-contained Wasm metadata")
    require(not any("available-at" in variant for variant in module["variants"]),
            "Wasm leaf must not redirect")
    require(manifest["regressions"] and set(manifest["testMethods"]) == REGRESSIONS,
            "Missing binary regression gate")
    marker = f"{base}/{stem}-kodex-build.json"
    raw[marker] = encoded(manifest)
    expected = dict(raw)
    for path, data in raw.items():
        for algorithm in ("sha1", "sha256", "sha512", "md5"):
            expected[path + "." + algorithm] = hashlib.new(algorithm, data).hexdigest().encode()
    return expected, marker


def seal(root, output):
    import shutil

    wanted = identity(root)
    source = root / SOURCE
    receipt = json.loads((source / "build/publication.json").read_text())
    require(all(receipt[name] == wanted[name] for name in ("commit", "tree", "version")),
            "Candidate does not match producer")
    regression_reports = source / "binary-probe/build/test-results/wasmJsBrowserTest"
    reports = verify_regressions(regression_reports)
    require(not output.exists(), "Seal output must be fresh")
    output.mkdir(parents=True)
    repo = output / "repo"
    repo.mkdir()
    for path, metadata in receipt["files"].items():
        require(path.startswith(f"{GROUP_PATH}/{wanted['version']}/") and
                ".." not in path.split("/") and "\\" not in path, "Unsafe payload path")
        data = (source / "build/verified-maven" / path).read_bytes()
        require(metadata == {"size": len(data), "sha256": digest(data)}, "Candidate changed")
        target = repo / path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(data)
    manifest = {
        "identity": wanted, "files": receipt["files"], "patchSha256": receipt["patchSha256"],
        "regressions": reports, "testMethods": sorted(REGRESSIONS),
    }
    (output / "bundle.json").write_bytes(encoded(manifest))
    payload(output, wanted)
    shutil.copytree(regression_reports, output / "binary-test-results")
    print(f"Sealed {wanted['version']}: five payloads, four executed regressions")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("seal", "publish"))
    parser.add_argument("--root", type=Path, default=Path.cwd())
    parser.add_argument("--bundle", type=Path, required=True)
    args = parser.parse_args()
    root = args.root.resolve()
    if args.command == "seal":
        seal(root, args.bundle.resolve())
        return
    require(os.environ.get("GITHUB_ACTIONS") == "true" and
            os.environ.get("GITHUB_REPOSITORY") == "Stream29/Kodex" and
            os.environ.get("GITHUB_REF") == "refs/heads/main" and
            os.environ.get("GITHUB_EVENT_NAME") in ("push", "workflow_dispatch"),
            "Only reviewed main CI can upload packages")
    require_trusted_event()
    require(git(root, "rev-parse", "HEAD") == os.environ.get("GITHUB_SHA"),
            "Checkout differs from the reviewed CI event")
    wanted = identity(root)
    expected, marker = payload(args.bundle.resolve(), wanted)
    manifest = json.loads((args.bundle / "bundle.json").read_text())
    require(verify_regressions(args.bundle / "binary-test-results") == manifest["regressions"],
            "Regression receipts changed")
    actor, token = os.environ.get("GITHUB_ACTOR"), os.environ.get("GITHUB_TOKEN")
    require(actor and token, "Missing CI package credentials")
    state = publish(MavenHTTP(ENDPOINT, actor, token), expected, marker,
                    lambda: live_main(os.environ["GITHUB_SHA"]))
    print(f"{state}: {wanted['version']}, {len(expected)} immutable files read back")


if __name__ == "__main__":
    main()
