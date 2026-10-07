#!/usr/bin/env python3
"""Build isolated binary-only consumers against a fully validated file repository."""
import argparse
import os
import platform
import re
import shutil
from pathlib import Path

from contract import FORKS, HOSTS, digest, encoded, require, smoke_gates
from pipeline import isolated_environment, run, verify_bundle, write_json

PROBES = {
    "mosaic": """import com.jakewharton.mosaic.focus.FocusRequester
import com.jakewharton.mosaic.tty.TestTerminal

fun main() {
    check(!FocusRequester().requestFocus())
    val terminal = TestTerminal.bind()
    try {
        terminal.resize(80, 24, 0, 0)
        check(terminal.tty.currentSize()[0] == 80)
    } finally {
        terminal.close()
    }
    println("Mosaic fork focus and real C payload passed")
}
""",
    "mcp": """import io.modelcontextprotocol.kotlin.sdk.client.CoroutineStdioSource
import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import kotlinx.coroutines.runBlocking
import kotlinx.io.Buffer

fun main() = runBlocking {
    val source = object : CoroutineStdioSource {
        override suspend fun readAtMostTo(sink: Buffer, byteCount: Long): Long = -1L
        override suspend fun close() {}
    }
    check(source.readAtMostTo(Buffer(), 1L) == -1L)
    StdioServerTransport(Buffer(), Buffer()).close()
    println("MCP fork async stdio and server passed")
}
""",
    "lucene": """import io.github.oshai.kotlinlogging.KotlinLoggingConfiguration
import io.github.oshai.kotlinlogging.Level
import org.gnit.lucenekmp.util.getLogger

fun main() {
    val config = KotlinLoggingConfiguration
    val previousLevel = config.direct.logLevel
    val previousFactory = config.loggerFactory
    val previousStartup = config.logStartupMessage
    try {
        config.direct.logLevel = Level.WARN
        config.logStartupMessage = true
        getLogger()
        check(config.direct.logLevel == Level.WARN)
        check(config.loggerFactory === previousFactory)
        check(config.logStartupMessage)
    } finally {
        config.direct.logLevel = previousLevel
        config.loggerFactory = previousFactory
        config.logStartupMessage = previousStartup
    }
    println("Lucene fork preserves host logging defaults")
}
""",
}

# JS has no runBlocking. This is a real binary-only consumer of the patched
# coroutine stdio constructor, core JSON-RPC/models and server API. No processes,
# HTTP requests or Native fixtures are launched by the Node probe.
MCP_JS_PROBE = """import io.ktor.client.HttpClient
import io.ktor.client.plugins.sse.SSE
import io.modelcontextprotocol.kotlin.sdk.LIB_VERSION
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.CoroutineStdioSink
import io.modelcontextprotocol.kotlin.sdk.client.CoroutineStdioSource
import io.modelcontextprotocol.kotlin.sdk.client.StdioClientTransport
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpClientTransport
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCNotification
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withTimeout
import kotlinx.io.Buffer
import kotlinx.io.readString

suspend fun main() {
    check(LIB_VERSION == "__FORK_VERSION__")
    val info = Implementation(name = "fork-js-probe", version = LIB_VERSION)
    val client = Client(info)
    check(client.serverVersion == null)
    client.close()
    val server = Server(info, ServerOptions(capabilities = ServerCapabilities()))
    server.close()

    var sourceClosed = false
    var errorClosed = false
    var sinkClosed = false
    val source = object : CoroutineStdioSource {
        override suspend fun readAtMostTo(sink: Buffer, byteCount: Long): Long = awaitCancellation()
        override suspend fun close() { sourceClosed = true }
    }
    val error = object : CoroutineStdioSource {
        override suspend fun readAtMostTo(sink: Buffer, byteCount: Long): Long = awaitCancellation()
        override suspend fun close() { errorClosed = true }
    }
    val written = Buffer()
    val flushed = CompletableDeferred<Unit>()
    val sink = object : CoroutineStdioSink {
        override suspend fun write(source: Buffer, byteCount: Long) { written.write(source, byteCount) }
        override suspend fun flush() { flushed.complete(Unit) }
        override suspend fun close() { sinkClosed = true }
    }
    val stdio = StdioClientTransport(input = source, output = sink, error = error)
    try {
        stdio.start()
        withTimeout(5000) {
            stdio.send(JSONRPCNotification(method = "notifications/initialized"))
            flushed.await()
        }
        val frame = written.readString()
        check(frame.contains("notifications/initialized") && frame.endsWith("\\n"))
    } finally {
        withTimeout(5000) { stdio.close() }
    }
    check(sourceClosed && errorClosed && sinkClosed)
    StdioServerTransport(Buffer(), Buffer()).close()

    // Match Kodex's borrowed-client semantics and ordinary Ktor JS engine defaults.
    // No mock engine, manual engine selection or localhost server hides missing JS dependencies.
    val http = HttpClient { install(SSE) }
    try {
        val transport = StreamableHttpClientTransport(client = http, url = "http://127.0.0.1:1/mcp")
        check(transport.sessionId == null)
        transport.close() // never start/connect: this probe performs no network I/O
    } finally {
        http.close()
    }
    println("MCP real JS stdio frame, models, server and Ktor defaults passed")
}
"""


def node_environment():
    executable = shutil.which("node")
    require(executable, "MCP JS smoke requires provided Node >=22.14.0 (CI setup-node)")
    version = run([executable, "--version"]).strip()
    require(re.fullmatch(r"v[0-9]+\.[0-9]+\.[0-9]+", version), "Unexpected Node version")
    require(tuple(map(int, version[1:].split("."))) >= (22, 14, 0),
            "MCP JS smoke requires Node >=22.14.0")
    return executable, version[1:]


def smoke(args):
    require(platform.system() == {"linux": "Linux", "mac": "Darwin", "windows": "Windows"}[args.host],
            "Wrong smoke host")
    isolated_environment(args.output)
    doc, _ = verify_bundle(args.bundle, args.fork, args.identity_sha256)
    require(not args.output.exists(), "Smoke output already exists")
    args.output.mkdir(parents=True)
    # Consumer has no fork sources, substitutions, included builds, mavenLocal or package fallback.
    version = doc["identity"]["version"]
    group = FORKS[args.fork]["group"]
    kgp = doc["hosts"][args.host]["toolchain"]["kgp"]
    java = os.environ["JAVA_HOME"]
    jvm_launcher = (Path(java) / "bin" / ("java.exe" if args.host == "windows" else "java")).as_posix()
    require("'" not in jvm_launcher, "Unsafe JVM launcher path")
    repo = (args.bundle / "repo").resolve().as_uri()
    settings = f"""pluginManagement {{
    repositories {{ gradlePluginPortal(); mavenCentral(); google() }}
}}
rootProject.name = 'fork-binary-smoke'
dependencyResolutionManagement {{
    repositories {{
        exclusiveContent {{
            forRepository {{ maven {{ url = uri('{repo}') }} }}
            filter {{ includeGroup('{group}') }}
        }}
        mavenCentral()
        google()
    }}
}}
"""
    (args.output / "settings.gradle").write_text(settings)
    targets = ("jvm",) + tuple(t for t in HOSTS[args.host] if t not in ("root", "jvm"))
    declarations = "\n".join(f"    {t}()" for t in targets)
    # The same probe must load on Java21 JNI and Java25 FFM. Compile with the
    # provided Java21 toolchain, not Gradle's running Java25 default bytecode.
    jvm_toolchain = "    jvmToolchain(21)\n" if args.fork == "mosaic" else ""
    js_gate = args.fork == "mcp" and args.host == "linux"
    js_configuration = ""
    node_configuration = ""
    if js_gate:
        executable, node_version = node_environment()
        require("'" not in executable and "\\" not in executable, "Unsafe Node executable path")
        declarations += "\n    js { nodejs(); binaries.executable() }"
        # Ktor's core-only SDK dependency cannot discover an engine by itself.
        # This is the same ordinary JS engine used by Kodex's Ktor defaults.
        js_configuration = """
    sourceSets.jsMain.dependencies {
        implementation('io.ktor:ktor-client-js:3.5.1')
    }
"""
        # KGP 2.4 EnvSpec uses Property values; do not use a Node wrapper/task replacement.
        node_configuration = f"""
extensions.configure(org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsEnvSpec) {{ spec ->
    spec.download.set(false)
    spec.command.set('{executable}')
    spec.version.set('{node_version}')
}}
"""
    dependencies = "\n".join(f"            implementation('{group}:{module}:{version}')"
                             for module in FORKS[args.fork]["modules"])
    if args.fork == "mcp":
        dependencies += "\n            implementation('org.jetbrains.kotlinx:kotlinx-io-core:0.9.1')"
    if args.fork == "lucene":
        dependencies += "\n            implementation('io.github.oshai:kotlin-logging:8.0.4')"
    dependencies += "\n            implementation('org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0')"
    jni_task = ""
    if args.fork == "mosaic":
        jdk21 = Path(os.environ["FORK_JDK21"])
        jni_java = jdk21 / "bin" / ("java.exe" if args.host == "windows" else "java")
        require(jni_java.is_file(), "Mosaic JNI gate requires the real Java21 launcher")
        launcher = jni_java.as_posix()
        require("'" not in launcher, "Unsafe Java21 launcher path")
        jni_task = f"""
tasks.register('verifyForkJvmJni', JavaExec) {{
    dependsOn('jvmMainClasses')
    def main = project.kotlin.targets.getByName('jvm').compilations.getByName('main')
    classpath = project.files(main.output.allOutputs, main.runtimeDependencyFiles)
    executable = '{launcher}'
    mainClass.set('ProbeKt')
    jvmArgs('--enable-native-access=ALL-UNNAMED')
}}
"""
    build = f"""plugins {{
    id 'org.jetbrains.kotlin.multiplatform' version '{kgp}'
}}
kotlin {{
{jvm_toolchain}
{declarations}
    sourceSets.commonMain {{
        dependencies {{
{dependencies}
        }}
    }}
{js_configuration}
    targets.configureEach {{ target ->
        if (target.platformType.name() == 'native') {{
            target.binaries.executable {{ entryPoint = 'main' }}
        }}
    }}
}}
{node_configuration}
tasks.register('verifyForkJvm', JavaExec) {{
    dependsOn('jvmMainClasses')
    def main = project.kotlin.targets.getByName('jvm').compilations.getByName('main')
    classpath = project.files(main.output.allOutputs, main.runtimeDependencyFiles)
    executable = '{jvm_launcher}'
    mainClass.set('ProbeKt')
    jvmArgs('--enable-native-access=ALL-UNNAMED')
}}
{jni_task}
"""
    (args.output / "build.gradle").write_text(build)
    probe_sets = [t + "Main" for t in targets] if js_gate else ["commonMain"]
    for source_set in probe_sets:
        probe = args.output / f"src/{source_set}/kotlin/Probe.kt"
        probe.parent.mkdir(parents=True)
        probe.write_text(PROBES[args.fork])
    if js_gate:
        probe = args.output / "src/jsMain/kotlin/Probe.kt"
        probe.parent.mkdir(parents=True)
        probe.write_text(MCP_JS_PROBE.replace("__FORK_VERSION__", version))
    wrapper_dir = args.root / FORKS[args.fork]["path"]
    wrapper = (["cmd", "/c", str(wrapper_dir / "gradlew.bat")] if args.host == "windows"
               else ["bash", str(wrapper_dir / "gradlew")])
    gates = smoke_gates(args.fork, args.host)
    tasks = gates["tasks"]
    command = wrapper + [
        "-p", str(args.output.resolve()), f"-Dorg.gradle.java.home={java}",
        "-Dorg.gradle.jvmargs=-Xmx2g -Dfile.encoding=UTF-8",
        "--max-workers=1", "--no-parallel", "--no-configuration-cache", "--no-build-cache", "--no-scan",
        "-Pkotlin.compiler.execution.strategy=daemon", "-Pkotlin.daemon.useFallbackStrategy=false",
        *([f"-Porg.gradle.java.installations.paths={java},{os.environ['FORK_JDK21']}",
           "-Porg.gradle.java.installations.auto-download=false"] if args.fork == "mosaic" else []),
        "-Pkotlin.daemon.jvmargs=-Xmx2g", "--console=plain", "--stacktrace", *tasks,
    ]
    try:
        run(command, cwd=args.output, capture=False)
    finally:
        run(wrapper + [f"-Dorg.gradle.java.home={java}", "--stop"], cwd=args.output, capture=False)
    # Receipt binds runtime gates to immutable manifest bytes; publisher verifies all three.
    write_json(args.receipt, {
        "schema": 1, "fork": args.fork, "host": args.host, "tasks": tasks,
        "manifestSha256": digest((args.bundle / "manifest.json").read_bytes()),
        "identitySha256": digest(encoded(doc["identity"])),
        "runtime": gates["runtime"], "compileOnly": gates["compileOnly"],
    })


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--fork", choices=FORKS, required=True)
    parser.add_argument("--host", choices=HOSTS, required=True)
    parser.add_argument("--root", type=Path, default=Path.cwd())
    parser.add_argument("--bundle", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--receipt", type=Path, required=True)
    parser.add_argument("--identity-sha256", required=True)
    smoke(parser.parse_args())


if __name__ == "__main__":
    main()
