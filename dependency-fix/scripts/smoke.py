#!/usr/bin/env python3
"""Build isolated binary-only consumers against a fully validated file repository."""
import argparse
import os
import platform
from pathlib import Path

from contract import FORKS, HOSTS, digest, encoded, require, smoke_gates
from pipeline import isolated_environment, run, verify_bundle, write_json

PROBES = {
    "ktor": """import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.*
import io.ktor.utils.io.*
import kotlinx.coroutines.*

fun main() = runBlocking {
    SelectorManager(Dispatchers.IO).use { selector ->
        val tcp = aSocket(selector).tcp()
        val server = tcp.bind("127.0.0.1", port = 0)
        try {
            coroutineScope {
                val worker = async {
                    val socket = server.accept()
                    try {
                        val input = socket.openReadChannel()
                        val output = socket.openWriteChannel(autoFlush = true)
                        repeat(16) {
                            check(input.readUTF8Line() == "ping")
                            output.writeStringUtf8("po")
                            output.flush()
                            output.writeStringUtf8("ng\\n")
                            output.flush()
                        }
                    } finally { socket.close(); socket.awaitClosed() }
                }
                val client = tcp.connect(server.localAddress)
                try {
                    val input = client.openReadChannel()
                    val output = client.openWriteChannel(autoFlush = true)
                    repeat(16) {
                        output.writeStringUtf8("ping\\n")
                        output.flush()
                        check(input.readUTF8Line() == "pong")
                    }
                } finally { client.close(); client.awaitClosed() }
                worker.await()
            }
        } finally { server.close(); server.awaitClosed() }
    }
    println("Ktor binary TCP exchange and cleanup passed")
}
""",
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
    "mcp": """import io.modelcontextprotocol.kotlin.sdk.LIB_VERSION
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.CoroutineStdioSource
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import kotlinx.coroutines.runBlocking
import kotlinx.io.Buffer

fun main() = runBlocking {
    val client = Client(Implementation(name = "fork-native-probe", version = LIB_VERSION))
    check(client.serverVersion == null)
    client.close()
    val source = object : CoroutineStdioSource {
        override suspend fun readAtMostTo(sink: Buffer, byteCount: Long): Long = -1L
        override suspend fun close() {}
    }
    check(source.readAtMostTo(Buffer(), 1L) == -1L)
    println("MCP fork async stdio and client passed")
}
""",
}


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
    repo_filter = (f"includeGroup('{group}')" if args.fork != "ktor" else "\n".join(
        f"includeVersion('io.ktor', 'ktor-network-{t.lower()}', '{version}')"
        for t in FORKS["ktor"]["targets"]))
    settings = f"""pluginManagement {{
    repositories {{ gradlePluginPortal(); mavenCentral(); google() }}
}}
rootProject.name = 'fork-binary-smoke'
dependencyResolutionManagement {{
    repositories {{
        exclusiveContent {{
            forRepository {{ maven {{ url = uri('{repo}') }} }}
            filter {{ {repo_filter} }}
        }}
        mavenCentral()
        google()
    }}
}}
"""
    (args.output / "settings.gradle").write_text(settings)
    native_targets = tuple(t for t in HOSTS[args.host] if t != "root")
    targets = (("jvm",) + native_targets) if args.fork == "ktor" else native_targets
    declarations = "\n".join(f"    {t}()" for t in targets)
    dependency_version = doc["identity"]["upstreamVersion"] if args.fork == "ktor" else version
    dependencies = "\n".join(f"            implementation('{group}:{module}:{dependency_version}')"
                             for module in FORKS[args.fork]["modules"])
    if args.fork == "mcp":
        dependencies += "\n            implementation('org.jetbrains.kotlinx:kotlinx-io-core:0.9.1')"
    dependencies += "\n            implementation('org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0')"
    jvm_task = ""
    network_rule = ""
    if args.fork == "ktor":
        jvm_task = f"""
tasks.register('verifyForkJvm', JavaExec) {{
    dependsOn('jvmMainClasses')
    def main = project.kotlin.targets.getByName('jvm').compilations.getByName('main')
    classpath = project.files(main.output.allOutputs, main.runtimeDependencyFiles)
    executable = '{jvm_launcher}'
    mainClass.set('ProbeKt')
    jvmArgs('--enable-native-access=ALL-UNNAMED')
}}
"""
        coordinates = ", ".join(f"'io.ktor:ktor-network-{t.lower()}:{version}'"
                                for t in FORKS["ktor"]["targets"])
        network_rule = f"""
configurations.configureEach {{ resolutionStrategy.force({coordinates}) }}
tasks.register('verifyNativeNetworkRepair') {{
    doLast {{
        kotlin.targets.findAll {{ it.platformType.name() == 'native' }}.each {{ target ->
            def artifacts = configurations.getByName(target.name + 'CompileKlibraries')
                .resolvedConfiguration.resolvedArtifacts.findAll {{
                    it.moduleVersion.id.group == 'io.ktor' &&
                    it.moduleVersion.id.name == 'ktor-network-' + target.name.toLowerCase()
                }}
            assert !artifacts.empty && artifacts.every {{ it.moduleVersion.id.version == '{version}' }}
        }}
        def jvm = configurations.jvmCompileClasspath.resolvedConfiguration.resolvedArtifacts
            .findAll {{ it.moduleVersion.id.name == 'ktor-network-jvm' }}
        assert !jvm.empty && jvm.every {{ it.moduleVersion.id.version == '{dependency_version}' }}
    }}
}}
"""
    build = f"""plugins {{
    id 'org.jetbrains.kotlin.multiplatform' version '{kgp}'
}}
kotlin {{
{declarations}
    sourceSets.commonMain {{
        dependencies {{
{dependencies}
        }}
    }}
    targets.configureEach {{ target ->
        if (target.platformType.name() == 'native') {{
            target.binaries.executable {{ entryPoint = 'main' }}
        }}
    }}
}}
{jvm_task}
{network_rule}
"""
    (args.output / "build.gradle").write_text(build)
    probe = args.output / "src/commonMain/kotlin/Probe.kt"
    probe.parent.mkdir(parents=True)
    probe.write_text(PROBES[args.fork])
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
