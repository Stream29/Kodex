"""Offline publication structure, not external resolution/compile certification."""
import hashlib
import json
import posixpath
import re
import stat
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path, PurePosixPath

TARGETS = ("jvm", "linuxX64", "linuxArm64", "macosArm64", "mingwX64")
HOSTS = {
    "linux": ("linuxX64", "linuxArm64"),
    "mac": ("root", "jvm", "macosArm64"),
    "windows": ("mingwX64",),
}
FORKS = {
    "mosaic": {
        "path": "Mosaic", "repository": "Stream29/mosaic",
        "group": "com.jakewharton.mosaic",
        "modules": {f"mosaic-{m}": f":mosaic-{m}" for m in
                    ("runtime", "animation", "testing", "terminal", "tty-terminal", "tty")},
    },
    "mcp": {
        "path": "KotlinMcpSdk", "repository": "Stream29/kotlin-mcp-sdk",
        "group": "io.modelcontextprotocol",
        "modules": {f"kotlin-sdk-{m}": f":kotlin-sdk-{m}" for m in ("core", "client", "server")},
        # The SDK's stdio/HTTP consumers really use JS on Node. No other fork exception.
        "targets": TARGETS + ("js",),
        "hosts": {**HOSTS, "mac": HOSTS["mac"] + ("js",)},
    },
    "lucene": {
        "path": "LuceneKmp", "repository": "Stream29/lucene-kmp",
        "group": "org.gnit.lucene-kmp", "modules": {"lucene-kmp-core": ":core"},
    },
}
NS = {"m": "http://maven.apache.org/POM/4.0.0"}
NATIVE_NAMES = {"linuxX64": "linux_x64", "linuxArm64": "linux_arm64",
                "macosArm64": "macos_arm64", "mingwX64": "mingw_x64"}
# Main project dependencies at the pinned Mosaic/SDK sources: (API, implementation).
PROJECT_EDGES = {
    "mosaic-runtime": (("mosaic-terminal",), ("mosaic-tty-terminal",)),
    "mosaic-animation": (("mosaic-runtime",), ()),
    "mosaic-testing": (("mosaic-runtime",), ()),
    "mosaic-tty-terminal": (("mosaic-terminal", "mosaic-tty"), ()),
    "kotlin-sdk-client": (("kotlin-sdk-core",), ()),
    "kotlin-sdk-server": (("kotlin-sdk-core",), ()),
}
TTY_PACKAGE = "com/jakewharton/mosaic/tty/"
TTY_JNI = {TTY_PACKAGE + f"jni/{arch}/{lib}" for arch, lib in (
    ("aarch64", "libmosaic.so"), ("riscv64", "libmosaic.so"), ("amd64", "libmosaic.so"),
    ("aarch64", "libmosaic.dylib"), ("x86_64", "libmosaic.dylib"),
    ("aarch64", "mosaic.dll"), ("amd64", "mosaic.dll"))}


def target_set(spec):
    return spec.get("targets", TARGETS)


def host_targets(spec, host):
    return spec.get("hosts", HOSTS)[host]


def target_closure(spec):
    # JSON-stable identity, shared by guard, stages and merged bundle.
    return {"targets": list(target_set(spec)),
            "hosts": {host: list(host_targets(spec, host)) for host in HOSTS}}


def smoke_gates(fork, host):
    native = {"linux": "LinuxX64", "mac": "MacosArm64", "windows": "MingwX64"}[host]
    tasks = ["verifyForkJvm", f"runDebugExecutable{native}"]
    runtime = ["jvm", native[0].lower() + native[1:]]
    if fork == "mosaic":
        tasks.append("verifyForkJvmJni")
        runtime.append("jvm-jni-java21")
    compile_only = ["linuxArm64"] if host == "linux" else []
    if host == "linux":
        tasks.append("compileKotlinLinuxArm64")
    if fork == "mcp" and host == "linux":
        tasks += ["compileKotlinJs", "jsNodeDevelopmentRun"]
        runtime.append("js")
    return {"tasks": tasks, "runtime": runtime, "compileOnly": compile_only}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def encoded(value):
    return (json.dumps(value, sort_keys=True, indent=2) + "\n").encode()


def digest(data):
    return hashlib.sha256(data).hexdigest()


def safe_name(name):
    require(isinstance(name, str) and name and "\\" not in name and
            not name.startswith("/") and not re.search(r"[:?#%\x00-\x20]", name) and
            all(p not in ("", ".", "..") for p in name.split("/")),
            "Unsafe artifact path")
    return name


def files(root):
    root = Path(root)
    require(root.is_dir() and not root.is_symlink(), "Unsafe repository directory")
    result = {}
    for path in sorted(root.rglob("*")):
        mode = path.lstat().st_mode
        require(not stat.S_ISLNK(mode), "Symlink in artifacts")
        if stat.S_ISDIR(mode):
            continue
        require(stat.S_ISREG(mode), "Nonregular artifact")
        require(path.stat().st_size <= 512 * 1024 * 1024, "Artifact exceeds bounded size")
        name = safe_name(path.relative_to(root).as_posix())
        result[name] = path.read_bytes()
    return result


def coordinate(spec, artifact, version):
    return f"{spec['group'].replace('.', '/')}/{artifact}/{version}"


def expected_artifacts(spec, targets=None):
    if targets is None:
        targets = ("root",) + target_set(spec)
    return {base if target == "root" else f"{base}-{target.lower()}": target
            for base in spec["modules"] for target in targets}


def local_reference(parent, relative):
    # Metadata may legitimately use ../../artifact/version paths, but never escape repo.
    require(isinstance(relative, str) and not re.search(r"[:\\?#%]", relative) and
            not relative.startswith("/"), "Unsafe metadata URL")
    result = posixpath.normpath(posixpath.join(parent, relative))
    safe_name(result)
    return result


def normalize_module(data, spec, version, root):
    doc = json.loads(data)
    require(doc.get("formatVersion") == "1.1", "Unexpected Gradle metadata format")
    # Gradle's per-invocation buildId isn't artifact identity.
    doc.get("createdBy", {}).get("gradle", {}).pop("buildId", None)
    if root:
        allowed = set(expected_artifacts(spec))
        kept = []
        for variant in doc["variants"]:
            link = variant.get("available-at")
            if link:
                require(link.get("group") == spec["group"] and link.get("version") == version,
                        "Unexpected root variant coordinate")
                if link.get("module") not in allowed:
                    continue
            kept.append(variant)
        doc["variants"] = kept
    return encoded(doc)


def variant_role(attributes, target):
    platform = "common" if target == "root" else (
        "native" if target in NATIVE_NAMES else target)
    require(attributes.get("org.jetbrains.kotlin.platform.type") == platform and
            (attributes.get("org.jetbrains.kotlin.native.target") == NATIVE_NAMES[target]
             if target in NATIVE_NAMES else "org.jetbrains.kotlin.native.target" not in attributes) and
            (attributes.get("org.jetbrains.kotlin.js.compiler") == "ir" if target == "js"
             else "org.jetbrains.kotlin.js.compiler" not in attributes),
            f"Wrong {'Native' if target in NATIVE_NAMES else target.upper()} variant attributes")
    require(attributes.get("org.gradle.jvm.environment") ==
            ("standard-jvm" if target == "jvm" else "non-jvm"), "Wrong variant JVM environment")
    usage = attributes.get("org.gradle.usage")
    if attributes.get("org.gradle.category") == "documentation":
        require(attributes.get("org.gradle.docstype") == "sources" and
                attributes.get("org.gradle.dependency.bundling") == "external" and
                usage == ("java-runtime" if target == "jvm" else "kotlin-runtime"),
                "Wrong source variant role")
        role = "sources"
    else:
        require(attributes.get("org.gradle.category") == "library" and
                "org.gradle.docstype" not in attributes, "Wrong library variant role")
        usages = ({"kotlin-metadata": "api"} if target == "root" else
                  {"java-api": "api", "java-runtime": "runtime"} if target == "jvm" else
                  {"kotlin-api": "api", "kotlin-runtime": "runtime"} if target == "js" else
                  {"kotlin-api": "api", "kotlin-metadata": "metadata"})
        require(usage in usages, "Wrong library variant usage")
        role = usages[usage]
    if target == "jvm":
        require(attributes.get("org.gradle.libraryelements") == "jar",
                "Wrong JVM library elements")
    return role


def required_edges(base, target, role):
    api, implementation = PROJECT_EDGES.get(base, ((), ()))
    # Native/metadata API exports implementation dependencies too; JVM/JS API does not.
    return set(api) | (set(implementation) if target not in ("jvm", "js") or role == "runtime"
                       else set())


def validate_root_metadata(archive, spec, base):
    path = "META-INF/kotlin-project-structure-metadata.json"
    require(path in archive.namelist(), "Missing KMP project structure metadata")
    structure = json.loads(archive.read(path)).get("projectStructure", {})
    source_sets = structure.get("sourceSets", [])
    names = {s.get("name") for s in source_sets}
    require(structure.get("formatVersion") and
            structure.get("isPublishedAsRoot") in (True, "true") and
            "commonMain" in names and structure.get("variants"),
            "Invalid KMP project structure")
    for source in source_sets:
        require(isinstance(source.get("name"), str) and
                set(source.get("dependsOn", [])) <= names and
                isinstance(source.get("moduleDependency"), list) and
                source.get("binaryLayout") == "klib", "Invalid KMP source set structure")
    for variant in structure["variants"]:
        require(variant.get("name") and variant.get("sourceSet") and
                set(variant["sourceSet"]) <= names, "Invalid KMP variant source sets")
    require({t + "ApiElements" for t in target_set(spec)} <=
            {v["name"] for v in structure["variants"] if "commonMain" in v["sourceSet"]},
            "Missing KMP platform source set mapping")
    common = next(s for s in source_sets if s["name"] == "commonMain")
    require({spec["group"] + ":" + d for d in required_edges(base, "root", "api")} <=
            set(common["moduleDependency"]), "Missing KMP metadata fork dependency")
    names = archive.namelist()
    require("commonMain/default/manifest" in names and
            any(n.startswith("commonMain/default/linkdata/") and n.endswith(".knm") and
                archive.getinfo(n).file_size > 0 for n in names),
            "Missing common Kotlin metadata payload")


def validate(repo, spec, version, targets=None, compiler_version=None, js_compiler_version=None):
    """Check local structure/essential fork edges; real consumers test compatibility."""
    inventory = files(repo)
    artifacts = expected_artifacts(spec, targets)
    seen = set()
    for name, data in inventory.items():
        parts = PurePosixPath(name).parts
        prefix = tuple(spec["group"].split("."))
        require(parts[:len(prefix)] == prefix and len(parts) == len(prefix) + 3,
                "Unexpected Maven layout")
        artifact, found_version, filename = parts[-3:]
        require(artifact in artifacts and found_version == version, "Unexpected coordinate")
        target = artifacts[artifact]
        base = artifact if target == "root" else artifact.removesuffix("-" + target.lower())
        stem = f"{artifact}-{version}"
        extension = ".jar" if artifacts[artifact] in ("root", "jvm") else ".klib"
        require(filename.startswith(stem + ".") or filename.startswith(stem + "-"),
                "Unexpected filename")
        require(filename in {stem + ext for ext in
                             (".pom", ".module", extension, "-sources.jar")} or
                (base == "mosaic-tty" and target in NATIVE_NAMES and
                 filename == stem + "-cinterop-mosaic.klib") or
                (target == "root" and filename == stem + "-kotlin-tooling-metadata.json") or
                (target in NATIVE_NAMES and filename == stem + "-metadata.jar"),
                "Unexpected publication attachment")
        require(data, "Empty artifact")
        if filename == stem + "-kotlin-tooling-metadata.json":
            tooling = json.loads(data)
            require(tooling.get("schemaVersion") == "1.1.0" and
                    tooling.get("buildSystem") == "Gradle" and
                    tooling.get("buildPlugin") ==
                    "org.jetbrains.kotlin.gradle.plugin.KotlinMultiplatformPluginWrapper" and
                    tooling.get("buildPluginVersion") and
                    isinstance(tooling.get("projectSettings"), dict) and
                    isinstance(tooling.get("projectTargets"), list) and
                    tooling["projectTargets"] and
                    all(isinstance(t, dict) and t.get("target") and t.get("platformType")
                        for t in tooling["projectTargets"]),
                    "Invalid Kotlin tooling metadata")
        seen.add(artifact)
    require(seen == set(artifacts), "Missing target publication")
    for artifact, target in artifacts.items():
        parent = coordinate(spec, artifact, version)
        stem = f"{parent}/{artifact}-{version}"
        base = artifact if target == "root" else artifact.removesuffix("-" + target.lower())
        require(stem + ".pom" in inventory and stem + ".module" in inventory,
                "Missing POM/module")
        require(b"<!DOCTYPE" not in inventory[stem + ".pom"] and
                b"<!ENTITY" not in inventory[stem + ".pom"], "Unsafe POM XML")
        pom = ET.fromstring(inventory[stem + ".pom"])
        for tag, wanted in (("groupId", spec["group"]), ("artifactId", artifact), ("version", version)):
            require(pom.findtext(f"m:{tag}", namespaces=NS) == wanted, "POM identity mismatch")
        pom_fork_deps = {}
        js_dependencies = set(spec["modules"]) | {base + "-js" for base in spec["modules"]}
        for dep in pom.findall("m:dependencies/m:dependency", NS):
            if dep.findtext("m:groupId", namespaces=NS) == spec["group"]:
                module = dep.findtext("m:artifactId", namespaces=NS)
                require(module in artifacts and
                        dep.findtext("m:version", namespaces=NS) == version,
                        "POM fork dependency outside closure")
                if target == "js":
                    require(module in js_dependencies, "JS POM fork dependency selects non-JS target")
                require(module in {d if target == "root" else f"{d}-{target.lower()}"
                                   for d in spec["modules"]}, "POM fork dependency selects wrong platform")
                if dep.findtext("m:optional", default="false", namespaces=NS) != "true":
                    pom_fork_deps[module] = dep.findtext("m:scope", default="compile", namespaces=NS)
        needed = required_edges(base, target, "runtime")
        pom_needed = {d if target == "root" else f"{d}-{target.lower()}" for d in needed}
        message = ("Missing SDK JS core dependency in POM/module" if target == "js" and needed else
                   "Missing essential POM fork dependency")
        # Real inspected roots declare common edges, not a mandatory JVM forwarding edge.
        require(pom_needed <= pom_fork_deps.keys(), message)
        api = set(PROJECT_EDGES.get(base, ((), ()))[0])
        require(all(pom_fork_deps[d if target == "root" else f"{d}-{target.lower()}"] ==
                    ("runtime" if target == "root" or (target in ("jvm", "js") and d not in api)
                     else "compile") for d in needed), "Wrong essential POM dependency scope")
        doc = json.loads(inventory[stem + ".module"])
        require(doc.get("formatVersion") == "1.1", "Unexpected module format")
        component = doc["component"]
        require(component.get("group") == spec["group"] and component.get("version") == version,
                "Module identity mismatch")
        if component.get("url"):
            require(target != "root" and component.get("module") == base,
                    "Target module mapped to wrong root")
            ref = local_reference(parent, component["url"])
            require(ref == f"{coordinate(spec, base, version)}/{base}-{version}.module" and
                    ref in inventory, "Dangling component URL")
            other = json.loads(inventory[ref])["component"]
            require(other["group"] == component["group"] and
                    other["module"] == component["module"] and other["version"] == version,
                    "Module component URL mismatch")
        else:
            require(component.get("module") == artifact, "Unexpected module component")
        payloads = set()
        available = set()
        roles = {}
        for variant in doc["variants"]:
            attributes = variant.get("attributes", {})
            link = variant.get("available-at")
            variant_target = target
            if link:
                require(target == "root" and link.get("module") in
                        {f"{base}-{t.lower()}" for t in target_set(spec)},
                        "Unexpected variant redirect")
                variant_target = next(t for t in target_set(spec)
                                      if link["module"] == f"{base}-{t.lower()}")
            try:
                role = variant_role(attributes, variant_target)
            except ValueError as error:
                if link and variant_target == "js":
                    raise ValueError("Wrong root JS redirect attributes") from error
                raise
            roles.setdefault(variant_target, set()).add(role)
            if not link and role != "sources":
                deps = {dep.get("module") for dep in variant.get("dependencies", [])
                        if dep.get("group") == spec["group"]}
                # Genuine target GMM uses root-module dependencies with platform selection.
                needed = required_edges(base, target, role)
                require(all(d in deps or (target != "root" and f"{d}-{target.lower()}" in deps)
                            for d in needed),
                        "Missing SDK JS core dependency in POM/module" if target == "js" and needed
                        else "Missing essential module fork dependency")
            for dep in variant.get("dependencies", []) + variant.get("dependencyConstraints", []):
                if dep.get("group") == spec["group"]:
                    require(dep.get("module") in artifacts and
                            dep.get("version", {}).get("requires") == version,
                            "Module fork dependency outside closure")
                    if target == "js":
                        require(dep["module"] in js_dependencies,
                                "JS module fork dependency selects non-JS target")
                    require(dep["module"] in set(spec["modules"]) |
                            ({f"{d}-{target.lower()}" for d in spec["modules"]}
                             if target != "root" else set()),
                            "Module fork dependency selects wrong platform")
            if link:
                require(target == "root" and link.get("group") == spec["group"] and
                        link.get("module") in artifacts and link.get("version") == version,
                        "Unexpected variant redirect")
                ref = local_reference(parent, link["url"])
                wanted = coordinate(spec, link["module"], version)
                require(ref == f"{wanted}/{link['module']}-{version}.module" and ref in inventory,
                        "Dangling variant redirect")
                available.add(link["module"])
                require(not variant.get("files"), "Redirect has embedded payload")
                destination = json.loads(inventory[ref])
                require(any(variant_role(v.get("attributes", {}), variant_target) == role
                            for v in destination["variants"]), "Redirect role missing at destination")
            else:
                extension = (".jar" if target in ("root", "jvm") else ".klib")
                attachment = ("-sources.jar" if role == "sources" else
                              "-metadata.jar" if role == "metadata" else extension)
                require(any(local_reference(parent, e["url"]) == stem + attachment
                            for e in variant.get("files", [])), "Missing variant role payload")
                allowed = {stem + attachment}
                if base == "mosaic-tty" and target in NATIVE_NAMES and role == "api":
                    allowed.add(stem + "-cinterop-mosaic.klib")
                require(all(local_reference(parent, e["url"]) in allowed
                            for e in variant.get("files", [])), "Payload assigned to wrong variant role")
            for entry in variant.get("files", []):
                ref = local_reference(parent, entry["url"])
                require(ref in inventory and posixpath.dirname(ref) == parent and
                        "/" not in safe_name(entry["name"]), "Dangling artifact reference")
                payload = inventory[ref]
                require(entry.get("size") == len(payload), "Artifact size mismatch")
                require(entry.get("sha256") == digest(payload), "Module checksum mismatch")
                for algorithm in ("sha512", "sha1", "md5"):
                    if algorithm in entry:
                        require(entry[algorithm] == hashlib.new(algorithm, payload).hexdigest(),
                                "Module checksum mismatch")
                payloads.add(ref)
        extension = ".jar" if target in ("root", "jvm") else ".klib"
        require(stem + extension in payloads, "Missing real component payload")
        require(stem + "-sources.jar" in payloads, "Missing published sources")
        for variant_target in (("root",) + target_set(spec) if target == "root" else (target,)):
            required = {"api", "sources"} | ({"runtime"} if variant_target in ("jvm", "js") else set())
            require(required <= roles.get(variant_target, set()),
                    "Root metadata target closure mismatch" if target == "root" else
                    "Missing API/runtime/source variant roles")
        if target == "root":
            require(available == {f"{artifact}-{t.lower()}" for t in target_set(spec)},
                    "Root metadata target closure mismatch")
        for ref in payloads:
            if ref.endswith((".jar", ".klib")):
                with zipfile.ZipFile(Path(repo) / ref) as archive:
                    names = archive.namelist()
                    require(len(names) <= 100000 and
                            sum(i.file_size for i in archive.infolist()) <= 512 * 1024 * 1024,
                            "Payload ZIP exceeds bounded size")
                    require(names and archive.testzip() is None, "Invalid payload ZIP")
                    require(all(not n.startswith("/") and "\\" not in n and
                                ":" not in n and ".." not in PurePosixPath(n).parts for n in names) and
                            all(not stat.S_ISLNK(info.external_attr >> 16)
                                for info in archive.infolist()),
                            "Unsafe payload ZIP")
                    if ref.endswith(".klib") and target not in ("root", "jvm"):
                        # KLIB manifests are Java properties; Windows writers may use CRLF.
                        manifest = archive.read("default/manifest").decode().replace("\r\n", "\n").replace("\r", "\n")
                        if target == "js":
                            require(re.search(r"(?m)^builtins_platform=JS$", manifest) and
                                    not re.search(r"(?m)^native_targets=", manifest),
                                    "Klib JS platform mismatch")
                            require(all(any(n.startswith(prefix) and archive.getinfo(n).file_size > 0
                                            for n in names)
                                        for prefix in ("default/ir/", "default/linkdata/")),
                                    "Missing real JS IR/metadata payload")
                            wanted_compiler = js_compiler_version
                        else:
                            require(re.search(rf"(?m)^native_targets={NATIVE_NAMES[target]}$", manifest),
                                    "Klib Native target mismatch")
                            if spec == FORKS["mcp"]:
                                require(re.search(r"(?m)^builtins_platform=NATIVE$", manifest),
                                        "SDK Klib Native platform mismatch")
                            wanted_compiler = compiler_version
                        if wanted_compiler:
                            require(re.search(rf"(?m)^compiler_version={re.escape(wanted_compiler)}$", manifest),
                                    "Klib compiler differs from recorded platform compiler")
                    if ref.endswith("-sources.jar"):
                        require(any(n.endswith(".kt") and archive.getinfo(n).file_size > 0 and
                                    re.search(rb"(?m)^\s*package\s+[A-Za-z_]", archive.read(n))
                                    for n in names),
                                "Missing JS Kotlin sources" if target == "js" else "Missing Kotlin sources")
                    if target == "root" and ref == stem + ".jar":
                        validate_root_metadata(archive, spec, base)
        # KGP publishes its diagnostic tooling JSON as a Maven classifier,
        # not a component file in GMM. It is validated above and still sealed/uploaded.
        directory_payloads = {n for n in inventory if posixpath.dirname(n) == parent and
                              n not in (stem + ".module", stem + ".pom",
                                        stem + "-kotlin-tooling-metadata.json")}
        require(payloads == directory_payloads, "Unexpected/unreferenced component attachment")
        if artifact == "mosaic-tty-jvm":
            with zipfile.ZipFile(Path(repo) / (stem + ".jar")) as archive:
                names = archive.namelist()
                classes = {TTY_PACKAGE + n + ".class" for n in ("Jni", "NativeLibrary")} | {
                    "META-INF/versions/22/" + TTY_PACKAGE + n + ".class"
                    for n in ("Libmosaic", "Tty", "TestTerminal", "StandardStreams")}
                require(TTY_JNI | classes <= set(names) and
                        all(archive.getinfo(n).file_size > 0 for n in TTY_JNI | classes) and
                        all(archive.read(n).startswith(b"\xca\xfe\xba\xbe") for n in classes) and
                        "META-INF/MANIFEST.MF" in names and
                        re.search(r"(?im)^Multi-Release: true\s*$",
                                  archive.read("META-INF/MANIFEST.MF").decode()),
                        "Missing Mosaic FFM/JNI payload")
        if base == "mosaic-tty" and target in NATIVE_NAMES:
            require(stem + "-cinterop-mosaic.klib" in payloads,
                    "Missing Mosaic Native cinterop")
            with zipfile.ZipFile(Path(repo) / (stem + ".klib")) as archive:
                require(any(n.endswith("/native/mosaic.bc") and archive.getinfo(n).file_size > 0
                            for n in archive.namelist()), "Missing Mosaic Native C bitcode")
    return inventory
