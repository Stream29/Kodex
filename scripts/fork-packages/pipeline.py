#!/usr/bin/env python3
"""Explicit fork-package CI commands; importing this module performs no operations."""
import argparse
import io
import json
import os
import platform
import posixpath
import re
import shutil
import subprocess
import tarfile
import zipfile
from pathlib import Path, PurePosixPath

from contract import (FORKS, HOSTS, coordinate, digest, encoded,
                      expected_artifacts, files, host_targets, normalize_module, require,
                      target_closure, target_set, validate)

HERE = Path(__file__).resolve().parent


def run(args, cwd=None, capture=True, timeout=None):
    return subprocess.run(args, cwd=cwd, check=True,
                          stdout=subprocess.PIPE if capture else None,
                          text=capture, timeout=timeout).stdout


def git(*args, cwd=None):
    return run(["git", *args], cwd=cwd, timeout=120).strip()


def live_main(expected):
    require(re.fullmatch(r"[0-9a-f]{40}", expected), "Invalid event SHA")
    actual = git("ls-remote", "https://github.com/Stream29/Kodex.git", "refs/heads/main").split()
    require(actual and actual[0] == expected, "Event SHA is no longer live main HEAD")


def identity(fork, root):
    spec = FORKS[fork]
    source = root / spec["path"]
    record = git("ls-tree", "HEAD", "--", spec["path"], cwd=root).split()
    require(len(record) == 4 and record[0] == "160000", "Not a gitlink")
    commit = record[2]
    require(git("rev-parse", "HEAD", cwd=source) == commit, "Fork HEAD differs from gitlink")
    require(not git("status", "--porcelain", "--untracked-files=all", cwd=source), "Dirty fork")
    expected_url = f"https://github.com/{spec['repository']}.git"
    # actions/checkout without ssh-key converts public git@github.com URLs to HTTPS.
    # persist-credentials:false removes checkout's temporary rewrite after cloning.
    # Reapply the SAME bridge in this read-only Git invocation, not a global config.
    url = git("-c", "url.https://github.com/.insteadOf=git@github.com:",
              "remote", "get-url", "origin", cwd=source)
    require(url == expected_url, "Checkout did not convert public SSH origin to HTTPS")
    git("fetch", "--no-tags", expected_url,
        "+refs/heads/kodex-submodule:refs/remotes/origin/kodex-submodule", cwd=source)
    run(["git", "merge-base", "--is-ancestor", commit, "refs/remotes/origin/kodex-submodule"],
        cwd=source)
    # Also inspect recursively initialized nested public submodules.
    nested = run(["git", "submodule", "status", "--recursive"], cwd=source).rstrip("\n")
    require(all(line.startswith(" ") for line in nested.splitlines()), "Unresolved nested gitlink")
    dirty = git("submodule", "foreach", "--quiet", "--recursive",
                "git status --porcelain --untracked-files=all", cwd=source)
    require(not dirty, "Dirty nested submodule")
    props = git("show", f"{commit}:gradle.properties", cwd=source)
    key = "VERSION_NAME" if fork == "mosaic" else "version"
    match = re.search(rf"(?m)^{key}=(.+)$", props)
    require(match is not None, "Missing upstream version")
    upstream = match.group(1).strip()
    require(re.fullmatch(r"[A-Za-z0-9._-]+", upstream), "Unsafe upstream version")
    return {
        "fork": fork, "repository": spec["repository"], "branch": "kodex-submodule",
        "commit": commit, "tree": git("rev-parse", f"{commit}^{{tree}}", cwd=source),
        "upstreamVersion": upstream, "version": f"{upstream}-kodex.{commit[:12]}",
        "targetClosure": target_closure(spec),
    }


def recipe(fork):
    result = {p.name: digest(p.read_bytes()) for p in sorted(HERE.iterdir())
              if p.suffix in (".py", ".gradle")}
    workflow = f".github/workflows/fork-packages-{fork}.yml"
    result[workflow] = digest((HERE.parent.parent / workflow).read_bytes())
    return result


def write_json(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(encoded(data))


def require_trusted_event():
    event = os.environ.get("GITHUB_EVENT_NAME")
    require((event == "push" and os.environ.get("GITHUB_REF_PROTECTED") == "true") or
            (event == "workflow_dispatch" and
             os.environ.get("FORK_PUBLICATION_REVIEWED") == "true"),
            "Publication requires a protected-main push or reviewed manual dispatch")


def guard(args):
    require(os.environ.get("GITHUB_REPOSITORY") == "Stream29/Kodex", "Unexpected repository")
    require(os.environ.get("GITHUB_REF") == "refs/heads/main", "Only main is authorized")
    require_trusted_event()
    require(git("rev-parse", "HEAD", cwd=args.root) == args.sha, "Checkout/event SHA mismatch")
    require(not git("status", "--porcelain", "--untracked-files=all", cwd=args.root),
            "Dirty main checkout")
    live_main(args.sha)
    data = identity(args.fork, args.root)
    write_json(args.output, data)
    if os.environ.get("GITHUB_OUTPUT"):
        with open(os.environ["GITHUB_OUTPUT"], "a", encoding="utf-8") as output:
            output.write(f"commit={data['commit']}\nversion={data['version']}\n"
                         f"identity_sha256={digest(encoded(data))}\n")


def task_set(fork, host):
    spec = FORKS[fork]
    labels = {"root": "KotlinMultiplatform", **{t: t[0].upper() + t[1:] for t in target_set(spec)}}
    return [f"{project}:publish{labels[t]}PublicationToForkStagingRepository"
            for project in spec["modules"].values() for t in host_targets(spec, host)]


def windows_resize_test_receipt(directory):
    """Observe the real targeted Native test output, not merely Gradle's exit code."""
    from xml.etree import ElementTree
    cases = []
    for report in directory.rglob("*.xml"):
        cases.extend(ElementTree.fromstring(report.read_bytes()).iter("testcase"))
    methods = {"successfulResizeDoesNotReturnStaleLastError", "failedResizeReportsActualWin32Error"}
    require(len(cases) == 2 and all(
        "WindowsConsoleResizeTest" in case.get("classname", "")
        and any(method in case.get("name", "") for method in methods)
        and not any(case.find(tag) is not None for tag in ("failure", "error", "skipped"))
        for case in cases
    ) and all(any(method in case.get("name", "") for case in cases) for method in methods),
            "Missing/failed targeted Windows console-resize tests")
    return {"task": ":mosaic-tty:mingwX64Test", "passed": 2, "methods": sorted(methods)}


def compiler_archive_evidence(paths, kgp):
    """Read the compiler's version resource, not stdlib's embedded build version."""
    evidence = []
    for path in sorted(paths):
        require(path.is_file() and not path.is_symlink(), "Unsafe compiler archive")
        with zipfile.ZipFile(path) as archive:
            version = archive.read("META-INF/compiler.version").decode().strip()
        require(re.fullmatch(re.escape(kgp) + r"(?:-[A-Za-z0-9.-]+)?", version),
                "Compiler archive outside pinned KGP release")
        evidence.append({"version": version, "sha256": digest(path.read_bytes())})
    require(evidence and all(e == evidence[0] for e in evidence), "Missing/mixed compiler archives")
    return evidence[0]


def native_compiler_evidence(kgp):
    # KGP's ordinary downloaded distributions/caches, under this run's private homes.
    konan = Path(os.environ["KONAN_DATA_DIR"])
    native_archives = [p for p in konan.glob("*/konan/lib/kotlin-native-compiler-embeddable.jar")
                       if p.parents[2].name.endswith("-" + kgp)]
    return compiler_archive_evidence(native_archives, kgp)


def mcp_compiler_evidence(kgp, host):
    result = {"native": native_compiler_evidence(kgp)}
    if "js" in host_targets(FORKS["mcp"], host):
        cache = Path(os.environ["GRADLE_USER_HOME"]) / "caches/modules-2/files-2.1"
        result["js"] = compiler_archive_evidence(
            (cache / "org.jetbrains.kotlin/kotlin-compiler-embeddable" / kgp).glob(
                f"*/kotlin-compiler-embeddable-{kgp}.jar"), kgp)
    return result


def isolated_environment(output):
    for variable, default in (("GRADLE_USER_HOME", ".gradle"), ("KONAN_DATA_DIR", ".konan")):
        value = os.environ.get(variable)
        require(value and Path(value).is_absolute(), f"Explicit private {variable} required")
        path = Path(value).resolve()
        require(path.is_relative_to(output.resolve().parent) and
                path != (Path.home() / default).resolve(),
                f"{variable} must be a private sibling under the staging parent")


def extract_source(archive, source):
    # A pinned Git tree can contain a benign document symlink (MCP CLAUDE.md).
    # Preserve safe in-tree links; Maven build outputs still reject ALL symlinks.
    with tarfile.open(fileobj=io.BytesIO(archive)) as tar:
        for member in tar.getmembers():
            require(member.isfile() or member.isdir() or member.issym(),
                    "Nonregular source archive member")
            require(not member.name.startswith("/") and "\\" not in member.name and
                    ":" not in member.name and ".." not in PurePosixPath(member.name).parts,
                    "Unsafe source archive")
            if member.issym():
                require(not member.linkname.startswith("/") and "\\" not in member.linkname and
                        ":" not in member.linkname, "Unsafe source symlink")
                target = posixpath.normpath(posixpath.join(posixpath.dirname(member.name), member.linkname))
                require(not target.startswith("../") and target != "..", "Escaping source symlink")
        tar.extractall(source, filter="data")


def prepare_android(host):
    value = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    require(value, "Standard runner Android SDK is missing; Lucene's original AGP requires it")
    sdk = Path(value)
    manager = sdk / "cmdline-tools/latest/bin" / ("sdkmanager.bat" if host == "windows" else "sdkmanager")
    require(manager.is_file(), "Standard runner sdkmanager is missing")
    required = {
        "platforms/android-36/android.jar",
        "platforms/android-36/source.properties",
        "build-tools/36.0.0/source.properties",
    }
    if not all((sdk / name).is_file() for name in required):
        launcher = ["cmd", "/c", str(manager)] if host == "windows" else [str(manager)]
        # Use runner-provided licenses. No license-acceptance or system-policy mutation.
        run(launcher + [f"--sdk_root={sdk}", "platforms;android-36", "build-tools;36.0.0"],
            capture=False, timeout=600)
    require(all((sdk / name).is_file() for name in required), "Incomplete SDK36/build-tools36 install")
    os.environ["ANDROID_HOME"] = str(sdk)
    return {"platform": "android-36", "buildTools": "36.0.0",
            "inputs": {name: digest((sdk / name).read_bytes()) for name in sorted(required)}}


def build(args):
    expected_os = {"linux": "Linux", "mac": "Darwin", "windows": "Windows"}
    require(platform.system() == expected_os[args.host], "Wrong publication host")
    if args.host == "mac":
        require(platform.machine().lower() in ("arm64", "aarch64"), "macOS Arm64 runner required")
    isolated_environment(args.output)
    require(git("rev-parse", "HEAD", cwd=args.root) == args.sha, "Build checkout/event SHA mismatch")
    live_main(args.sha)
    data = identity(args.fork, args.root)
    require(not args.output.exists(), "Staging path already exists")
    args.output.mkdir(parents=True)
    source = args.output / "source"
    source.mkdir()
    archive = subprocess.run(["git", "archive", data["commit"]],
                             cwd=args.root / FORKS[args.fork]["path"],
                             check=True, stdout=subprocess.PIPE).stdout
    extract_source(archive, source)
    # git archive omits nested gitlink contents. Fail rather than silently building incomplete input.
    require(not (source / ".gitmodules").exists(), "Nested build sources need explicit archive support")
    stage = args.output / "stage"
    stage.mkdir()
    os.environ["FORK_STAGING_REPO"] = str(stage.resolve())
    os.environ["FORK_VERSION"] = data["version"]
    os.environ["FORK_SOURCE_DIR"] = str(source.resolve())
    os.environ["FORK_NAME"] = args.fork
    java = Path(os.environ["JAVA_HOME"])
    jdk21 = Path(os.environ["FORK_JDK21"])
    android = prepare_android(args.host) if args.fork == "lucene" else None
    tasks = task_set(args.fork, args.host)
    wrapper = ["cmd", "/c", "gradlew.bat"] if args.host == "windows" else ["bash", "gradlew"]
    # Native compiler work can live in the Gradle JVM. Preserve the proven
    # Lucene 4g/one-worker publication budget instead of moving it to a JVM-only daemon.
    gradle_heap, kotlin_heap = ("4g", "2g") if args.fork == "lucene" else ("2g", "2g")
    command = wrapper + [
        f"-Dorg.gradle.java.home={java}",
        f"-Dorg.gradle.jvmargs=-Xmx{gradle_heap} -Dfile.encoding=UTF-8",
        "--max-workers=1", "--no-parallel", "--no-configuration-cache", "--no-build-cache", "--no-scan",
        "-Pkotlin.compiler.execution.strategy=daemon",
        "-Pkotlin.daemon.useFallbackStrategy=false", f"-Pkotlin.daemon.jvmargs=-Xmx{kotlin_heap}",
        f"-Porg.gradle.java.installations.paths={java},{jdk21}",
        "-Porg.gradle.java.installations.auto-download=false",
        f"-Pversion={data['version']}",
        # Lucene establishes coordinates itself; VERSION_NAME makes Vanniktech
        # finalize its version before that callback, so do not inject a second
        # version property into that fork.
        *([f"-PVERSION_NAME={data['version']}"] if args.fork != "lucene" else []),
        "-PmavenCentralPublishing=false", "-PsignAllPublications=false",
        "-I", str(HERE / "publish.init.gradle"), "--console=plain", "--stacktrace", *tasks,
    ]
    # Dedicated fresh hosted-runner daemon: explicit Java home, no user/shared daemon takeover.
    try:
        run(command, cwd=source, capture=False)
        resize_receipt = None
        if args.fork == "mosaic" and args.host == "windows":
            test_command = command[:-len(tasks)] + [
                ":mosaic-tty:mingwX64Test", "--tests",
                "com.jakewharton.mosaic.tty.WindowsConsoleResizeTest",
            ]
            run(test_command, cwd=source, capture=False)
            resize_receipt = windows_resize_test_receipt(
                source / "mosaic-tty/build/test-results/mingwX64Test")
    finally:
        run(wrapper + [f"-Dorg.gradle.java.home={java}", "--stop"], cwd=source, capture=False)
    toolchain = json.loads((args.output / "toolchain.json").read_bytes())
    if resize_receipt is not None:
        toolchain["windowsResizeRegression"] = resize_receipt
    # java -version uses stderr; collect separately without mixing secrets/environment.
    info = subprocess.run([str(jdk21 / "bin" / ("java.exe" if args.host == "windows" else "java")),
                           "-version"], check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    toolchain["java21"] = info.stderr.decode().strip()
    toolchain["compilerArchives"] = {"native": native_compiler_evidence(toolchain["kgp"])}
    toolchain["nativeCompiler"] = toolchain["compilerArchives"]["native"]["version"]
    if args.fork == "mcp":
        toolchain["compilerArchives"] = mcp_compiler_evidence(toolchain["kgp"], args.host)
        toolchain["nativeCompiler"] = toolchain["compilerArchives"]["native"]["version"]
        if args.host == "mac":
            toolchain["jsCompiler"] = toolchain["compilerArchives"]["js"]["version"]
    toolchain["gradleHeap"] = gradle_heap
    toolchain["kotlinHeap"] = kotlin_heap
    if android:
        toolchain["androidSdk"] = android
    if args.fork == "mosaic":
        toolchain["declaredNativeTools"] = {
            "zig": "0.15.1", "cklib": "0.3.5", "jextract": "1.0.0",
        }
        if args.host == "mac":
            toolchain["appleSdk"] = run(["xcrun", "--sdk", "macosx", "--show-sdk-version"]).strip()
            sdk_env = dict(os.environ, DEVELOPER_DIR=os.environ["FORK_DEVELOPER_DIR"])
            toolchain["jniAppleSdk"] = subprocess.run(
                ["xcrun", "--sdk", "macosx", "--show-sdk-version"], env=sdk_env,
                check=True, stdout=subprocess.PIPE, text=True).stdout.strip()
    toolchain["sourceInputs"] = {
        name: digest((source / name).read_bytes()) for name in
        ("gradle.properties", "gradle/libs.versions.toml", "gradle/wrapper/gradle-wrapper.properties",
         "gradle/wrapper/gradle-wrapper.jar")
    }
    write_json(args.output / "toolchain.json", toolchain)
    # Stage only each host's authoritative publications. Never merge host-specific root lists.
    out_repo = args.output / "repo"
    allowed = expected_artifacts(FORKS[args.fork], host_targets(FORKS[args.fork], args.host))
    raw = files(stage)
    selected = {}
    for name, content in raw.items():
        # Gradle file-repository rolling metadata/checksums are not immutable version payload.
        if "/maven-metadata.xml" in name or name.endswith((".md5", ".sha1", ".sha256", ".sha512")):
            continue
        artifact = Path(name).parts[-3]
        require(artifact in allowed, "Unexpected host publication")
        if name.endswith(".module"):
            content = normalize_module(content, FORKS[args.fork], data["version"],
                                       allowed[artifact] == "root")
        selected[name] = content
        destination = out_repo / name
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_bytes(content)
    require(selected, "Empty publication output")
    write_json(args.output / "stage.json", {
        "identity": data, "host": args.host, "targets": host_targets(FORKS[args.fork], args.host),
        "recipe": recipe(args.fork), "toolchain": toolchain, "tasks": tasks,
        "files": {n: {"sha256": digest(b), "size": len(b)} for n, b in selected.items()},
    })
    # Only sealed artifacts are uploaded; source/stage/daemon caches stay on ephemeral runner.
    shutil.rmtree(source)
    shutil.rmtree(stage)


def merge(args):
    require(not args.output.exists(), "Merge destination already exists")
    identity_value = None
    seen_hosts = set()
    tools = {}
    for entry in sorted(args.input.iterdir()):
        require(entry.is_dir() and not entry.is_symlink(), "Unexpected stage artifact")
        require(set(files(entry)) == {"stage.json", "toolchain.json"} |
                {"repo/" + n for n in files(entry / "repo")}, "Unexpected stage files")
        doc = json.loads((entry / "stage.json").read_bytes())
        host = doc["host"]
        require(host in HOSTS and host not in seen_hosts and
                tuple(doc["targets"]) == host_targets(FORKS[args.fork], host),
                "Unexpected/duplicate stage host")
        require(doc["identity"]["fork"] == args.fork and doc["recipe"] == recipe(args.fork),
                "Stage recipe/fork mismatch")
        if identity_value is None:
            identity_value = doc["identity"]
        require(doc["identity"] == identity_value, "Mixed source identities")
        require(re.fullmatch(r"[0-9a-f]{40}", identity_value["commit"]) and
                re.fullmatch(r"[0-9a-f]{40}", identity_value["tree"]), "Invalid commit/tree")
        raw = files(entry / "repo")
        require(json.loads((entry / "toolchain.json").read_bytes()) == doc["toolchain"],
                "Stage toolchain evidence mismatch")
        require({n: {"sha256": digest(b), "size": len(b)} for n, b in raw.items()} == doc["files"],
                "Stage checksum mismatch")
        allowed = expected_artifacts(FORKS[args.fork], host_targets(FORKS[args.fork], host))
        require(all(Path(n).parts[-3] in allowed for n in raw), "Cross-host publication collision")
        for name, data in raw.items():
            dest = args.output / "repo" / name
            require(not dest.exists(), "Duplicate artifact path")
            dest.parent.mkdir(parents=True, exist_ok=True)
            dest.write_bytes(data)
        seen_hosts.add(host)
        tools[host] = {"toolchain": doc["toolchain"], "tasks": doc["tasks"]}
    require(seen_hosts == set(HOSTS), "Missing build host")
    version = identity_value["version"]
    raw = validate(args.output / "repo", FORKS[args.fork], version,
                   compiler_version=tools["mac"]["toolchain"].get("nativeCompiler",
                                                                tools["mac"]["toolchain"]["kgp"]),
                   js_compiler_version=tools["mac"]["toolchain"].get("jsCompiler"))
    require(len({tools[h]["toolchain"]["kgp"] for h in HOSTS}) == 1 and
            len({tools[h]["toolchain"]["gradle"] for h in HOSTS}) == 1 and
            all(tools[h]["toolchain"]["sourceInputs"] == tools["mac"]["toolchain"]["sourceInputs"]
                for h in HOSTS), "Mixed pinned toolchains/source inputs")
    manifest = {
        "schema": 1, "identity": identity_value, "recipe": recipe(args.fork),
        "publicationRepository": "Stream29/Kodex",
        "coordinates": {"group": FORKS[args.fork]["group"],
                        "modules": sorted(FORKS[args.fork]["modules"])},
        "targets": target_set(FORKS[args.fork]), "hosts": tools,
        "validation": {"metadataClosure": True, "remoteVerified": False,
                       "runtime": "separate host smoke gates; not all architectures executed"},
        "files": {n: {"sha256": digest(b), "size": len(b)} for n, b in raw.items()},
    }
    write_json(args.output / "manifest.json", manifest)
    verify_bundle(args.output, args.fork, args.identity_sha256)


def verify_bundle(bundle, fork, identity_sha256=None):
    doc = json.loads((bundle / "manifest.json").read_bytes())
    require(doc["schema"] == 1 and doc["identity"]["fork"] == fork and doc["recipe"] == recipe(fork),
            "Unexpected bundle manifest")
    require(doc.get("publicationRepository") == "Stream29/Kodex", "Unexpected package repository")
    require(tuple(doc["targets"]) == target_set(FORKS[fork]) and
            doc["coordinates"] == {"group": FORKS[fork]["group"], "modules": sorted(FORKS[fork]["modules"])},
            "Unexpected manifest closure")
    ident = doc["identity"]
    require(ident.get("targetClosure") == target_closure(FORKS[fork]),
            "Guard identity target closure mismatch")
    if identity_sha256:
        require(digest(encoded(ident)) == identity_sha256, "Bundle does not match the guarded gitlink")
    require(ident["repository"] == FORKS[fork]["repository"] and ident["branch"] == "kodex-submodule" and
            re.fullmatch(r"[0-9a-f]{40}", ident["commit"]) and
            re.fullmatch(r"[0-9a-f]{40}", ident["tree"]) and
            ident["version"] == ident["upstreamVersion"] + "-kodex." + ident["commit"][:12],
            "Invalid manifest source identity")
    require(set(doc["hosts"]) == set(HOSTS) and
            all(doc["hosts"][h]["tasks"] == task_set(fork, h) for h in HOSTS),
            "Unexpected manifest host/task closure")
    if fork == "mcp":
        native_versions = set()
        for host in HOSTS:
            toolchain = doc["hosts"][host]["toolchain"]
            archives = toolchain.get("compilerArchives", {})
            wanted = {"native", "js"} if host == "mac" else {"native"}
            require(set(archives) == wanted, "Missing SDK platform compiler evidence")
            for evidence in archives.values():
                require(set(evidence) == {"version", "sha256"} and
                        re.fullmatch(re.escape(toolchain["kgp"]) + r"(?:-[A-Za-z0-9.-]+)?",
                                     evidence["version"]) and
                        re.fullmatch(r"[0-9a-f]{64}", evidence["sha256"]),
                        "Invalid SDK compiler evidence")
            require(toolchain.get("nativeCompiler") == archives["native"]["version"],
                    "SDK Native compiler evidence mismatch")
            native_versions.add(toolchain["nativeCompiler"])
            if host == "mac":
                require(toolchain.get("jsCompiler") == archives["js"]["version"],
                        "SDK JS compiler evidence mismatch")
        require(len(native_versions) == 1, "Mixed SDK Native compiler versions")
    raw = validate(bundle / "repo", FORKS[fork], ident["version"],
                   compiler_version=doc["hosts"]["mac"]["toolchain"].get(
                       "nativeCompiler", doc["hosts"]["mac"]["toolchain"]["kgp"]),
                   js_compiler_version=doc["hosts"]["mac"]["toolchain"].get("jsCompiler"))
    require({n: {"sha256": digest(b), "size": len(b)} for n, b in raw.items()} == doc["files"],
            "Bundle checksum mismatch")
    require(set(files(bundle)) == {"manifest.json"} | {"repo/" + n for n in raw},
            "Unexpected bundle files")
    return doc, raw


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("guard", "build", "merge", "verify", "tasks"))
    parser.add_argument("--fork", choices=FORKS, required=True)
    parser.add_argument("--root", type=Path, default=Path.cwd())
    parser.add_argument("--sha", default=os.environ.get("GITHUB_SHA", ""))
    parser.add_argument("--host", choices=HOSTS)
    parser.add_argument("--input", type=Path)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--identity-sha256")
    args = parser.parse_args()
    if args.command == "tasks":
        require(args.host in HOSTS, "Specify --host")
        print("\n".join(task_set(args.fork, args.host)))
    elif args.command == "guard":
        guard(args)
    elif args.command == "build":
        require(args.host in HOSTS, "Specify --host")
        build(args)
    elif args.command == "merge":
        require(args.identity_sha256 is not None, "Specify guarded --identity-sha256")
        merge(args)
    else:
        verify_bundle(args.input, args.fork, args.identity_sha256)
        print("Validated local immutable bundle; no remote publication inferred")


if __name__ == "__main__":
    main()
