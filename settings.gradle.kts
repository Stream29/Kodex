import org.gradle.api.initialization.Settings
import org.gradle.authentication.http.BasicAuthentication

pluginManagement {
    includeBuild("build-logic")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

@Suppress("UnstableApiUsage")
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_PROJECT)
    repositories {
        exclusiveContent {
            forRepository {
                maven {
                    name = "kodexForkPackages"
                    url = uri("https://maven.pkg.github.com/Stream29/Kodex")
                    credentials {
                        username = providers.gradleProperty("gpr.user")
                            .orElse(providers.environmentVariable("GITHUB_ACTOR")).orNull
                        password = providers.gradleProperty("gpr.key")
                            .orElse(providers.environmentVariable("GITHUB_TOKEN")).orNull
                    }
                    authentication {
                        create<BasicAuthentication>("basic")
                    }
                }
            }
            filter {
                includeGroup("com.jakewharton.mosaic")
                includeGroup("io.modelcontextprotocol")
                includeVersionByRegex(
                    "io\\.ktor",
                    "ktor-network-(linuxx64|linuxarm64|macosarm64|mingwx64)",
                    "[0-9]+\\.[0-9]+\\.[0-9]+-kodex\\.[0-9a-f]{12}",
                )
                includeVersionByRegex(
                    "org\\.jetbrains\\.compose\\.ui",
                    "ui-wasm-js",
                    "1\\.11\\.1-kodex\\.[0-9a-f]{12}",
                )
            }
        }
        google()
        mavenCentral()
    }
}

rootProject.name = "Kodex"

includeBuild("lint")

val declaredModulePaths = mutableSetOf<String>()

class ModuleTreeScope(
    private val settings: Settings,
    private val segments: List<String>,
    private val declaredPaths: MutableSet<String>,
) {
    private val path = segments.joinToString("/")

    init {
        require(segments.isNotEmpty() && segments.all { it.matches(Regex("[a-z0-9]+(?:-[a-z0-9]+)*")) }) {
            "Invalid module path: $path"
        }
        require(segments.none { it == "spec" || it == "impl" }) {
            "Use business module names (contract for shared protocol or cycle-breaking boundaries): $path"
        }
        val directory = settings.settingsDir.resolve(path)
        require(directory.isDirectory) { "Missing module directory: $path" }
        require(declaredPaths.add(path)) { "Duplicate declared module path: $path" }
        if (directory.resolve("build.gradle.kts").isFile) {
            val projectPath = ":${segments.joinToString("-")}"
            require(settings.findProject(projectPath) == null) {
                "Duplicate module path or flat project ID: $path -> $projectPath"
            }
            settings.include(projectPath)
            settings.project(projectPath).projectDir = directory
        }
    }

    fun moduleTree(name: String, block: ModuleTreeScope.() -> Unit) {
        ModuleTreeScope(settings, segments + name, declaredPaths).block()
    }

    fun module(name: String) {
        require(name.matches(Regex("[a-z0-9]+(?:-[a-z0-9]+)*"))) { "Invalid module segment: $name" }
        val childPath = "$path/$name"
        require(settings.settingsDir.resolve("$childPath/build.gradle.kts").isFile) {
            "Leaf module requires build.gradle.kts: $childPath"
        }
        ModuleTreeScope(settings, segments + name, declaredPaths)
    }
}

fun Settings.moduleTree(name: String, block: ModuleTreeScope.() -> Unit) {
    ModuleTreeScope(this, listOf(name), declaredModulePaths).block()
}

moduleTree("agent-context") {
    module("settings")
}

moduleTree("agent-eventbus") {
}

moduleTree("agent-schedule") {
    module("contract")
    module("sqlite")
}

moduleTree("agent-runtime") {
    module("composition")
    module("contract")
}

moduleTree("agent-session") {
    module("sqlite")
    module("contract")
    module("test")
}

moduleTree("agent-state") {
    module("contract")
    module("core")
    module("test")
}

moduleTree("agent-storage") {
    module("sqlite")
    module("clean-models")
    module("core")
    module("filesystem-layout")
    module("in-memory")
}

moduleTree("app") {
    moduleTree("backend") {
        module("daemon")
        module("host")
        module("migration")
        module("settings")
    }
    module("cli")
    moduleTree("component") {
        moduleTree("account-usage") {
            module("view")
            module("viewmodel")
        }
        moduleTree("agent") {
            module("contract")
            module("view")
        }
        moduleTree("application") {
            module("view")
            module("viewmodel")
        }
        moduleTree("application-preferences") {
            module("view")
            module("viewmodel")
        }
        moduleTree("authentication-settings") {
            module("view")
            module("viewmodel")
        }
        moduleTree("composer") {
            module("view")
            module("viewmodel")
        }
        moduleTree("context-source-settings") {
            module("view")
            module("viewmodel")
        }
        module("formatting")
        moduleTree("history") {
            module("presentation")
            module("view")
            module("viewmodel")
        }
        moduleTree("history-index") {
            module("view")
            module("viewmodel")
        }
        moduleTree("hook-settings") {
            module("view")
            module("viewmodel")
        }
        moduleTree("mcp-settings") {
            module("view")
            module("viewmodel")
        }
        moduleTree("new-session") {
            module("view")
            module("viewmodel")
        }
        moduleTree("new-session-defaults") {
            module("view")
            module("viewmodel")
        }
        moduleTree("openai-login") {
            module("view")
            module("viewmodel")
        }
        moduleTree("path-picker") {
            module("view")
            module("viewmodel")
        }
        moduleTree("request-user-input") {
            module("view")
            module("viewmodel")
        }
        moduleTree("runtime-configuration") {
            module("view")
            module("viewmodel")
        }
        moduleTree("session") {
            module("contract")
            module("viewmodel")
        }
        moduleTree("session-catalog") {
            module("view")
            module("viewmodel")
        }
        moduleTree("session-delete") {
            module("view")
            module("viewmodel")
        }
        moduleTree("session-rename") {
            module("view")
            module("viewmodel")
        }
        moduleTree("session-settings") {
            module("view")
            module("viewmodel")
        }
        moduleTree("session-sidebar") {
            module("view")
        }
        moduleTree("session-tab-bar") {
            module("view")
        }
        moduleTree("session-title-settings") {
            module("view")
            module("viewmodel")
        }
        moduleTree("settings") {
            module("view")
            module("viewmodel")
        }
        moduleTree("usage-reset") {
            module("view")
            module("viewmodel")
        }
        module("widgets")
        moduleTree("working-directory") {
            module("view")
            module("viewmodel")
        }
    }
    moduleTree("rpc") {
        module("application")
        module("new-session")
        module("protocol")
        module("session")
        module("system")
        module("test-support")
    }
    moduleTree("tui") {
        module("components")
        module("host")
        module("path-picker")
        module("renderer")
    }
    moduleTree("web") {
        module("host")
        module("renderer")
        moduleTree("test-support") {
            module("backend")
            module("renderer")
        }
        module("theme")
    }
}

moduleTree("hook") {
    module("notification")
}

moduleTree("integration-test") {
}

moduleTree("mcp") {
    module("contract")
    module("backend")
}

moduleTree("openai") {
    moduleTree("account-usage") {
        module("backend")
    }
    module("client")
    module("client-test")
    module("codex-cli-storage")
    module("json-codec")
    module("model-catalog")
    module("models")
}

moduleTree("rpc") {
    module("browser-client")
    module("connection")
    module("backend-control-client")
    module("client")
    module("in-memory")
    module("krpc-utils-patch")
    module("local")
    module("local-server")
    module("models")
    module("server")
    module("websocket")
}

moduleTree("tool") {
    module("apply-patch")
    module("await-event")
    module("core")
    module("image-generation")
    module("plan")
    moduleTree("read-media") {
        module("contract")
    }
    module("request-user-input")
    moduleTree("unified-exec") {
        module("contract")
    }
    module("web-run")
}

moduleTree("utils") {
    module("sqlite")
    module("coroutines")
    module("external-url")
    module("filesystem-lease")
    module("images")
    module("kauri")
    module("kodex-home")
    module("kotlinx-io-coroutines")
    module("kotlinx-io-serialization")
    module("ktor-client-ext")
    moduleTree("logging") {
        module("backend-file")
        module("backend-scope")
    }
    module("os-environment")
    module("oauth-loopback")
    module("patch")
    module("process-client")
    module("read-write-mutex")
    module("rpc-exception")
    moduleTree("shell-client") {
        module("system")
    }
    module("terminal-text")
}
