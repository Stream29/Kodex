import org.gradle.api.initialization.Settings
import org.gradle.authentication.http.BasicAuthentication

pluginManagement {
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
                includeGroup("org.gnit.lucene-kmp")
                includeGroup("io.modelcontextprotocol")
            }
        }
        google()
        mavenCentral()
    }
}

rootProject.name = "Kodex"

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
        val directory = settings.settingsDir.resolve(path)
        require(directory.isDirectory) { "Missing module directory: $path" }
        require(declaredPaths.add(path)) { "Duplicate declared module path: $path" }
        if (segments.size == 1 || directory.resolve("build.gradle.kts").isFile) {
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

moduleTree("integration-test") {
}

moduleTree("app") {
    module("cli")
    moduleTree("component") {
        moduleTree("account-usage") {
            moduleTree("impl") {
                module("view")
                module("viewmodel")
            }
            module("spec")
        }
        moduleTree("agent") {
            moduleTree("impl") {
                module("view")
            }
            module("spec")
        }
        moduleTree("application-preferences") {
            moduleTree("impl") {
                module("view")
                module("viewmodel")
            }
            module("spec")
        }
        moduleTree("authentication-settings") {
            moduleTree("impl") {
                module("view")
                module("viewmodel")
            }
            module("spec")
        }
        moduleTree("composer") {
            moduleTree("impl") {
                module("view")
                module("viewmodel")
            }
            module("spec")
        }
        moduleTree("context-source-settings") {
            moduleTree("impl") {
                module("view")
                module("viewmodel")
            }
            module("spec")
        }
        moduleTree("history-index") {
            moduleTree("impl") {
                module("view")
                module("viewmodel")
            }
            module("spec")
        }
        moduleTree("history") {
            moduleTree("impl") {
                module("view")
                module("viewmodel")
            }
            module("spec")
        }
        moduleTree("hook-settings") {
            moduleTree("impl") {
                module("view")
                module("viewmodel")
            }
            module("spec")
        }
        moduleTree("mcp-settings") {
            moduleTree("impl") {
                module("view")
                module("viewmodel")
            }
            module("spec")
        }
        moduleTree("new-session-defaults") {
            moduleTree("impl") {
                module("view")
                module("viewmodel")
            }
            module("spec")
        }
        moduleTree("new-session") {
            moduleTree("impl") {
                module("view")
                module("viewmodel")
            }
            module("spec")
        }
        moduleTree("openai-login") {
            moduleTree("impl") {
                module("view")
                module("viewmodel")
            }
            module("spec")
        }
        moduleTree("path-picker") {
            moduleTree("impl") {
                module("view")
                module("viewmodel")
            }
            module("spec")
        }
        moduleTree("request-user-input") {
            moduleTree("impl") {
                module("view")
                module("viewmodel")
            }
            module("spec")
        }
        moduleTree("runtime-configuration") {
            moduleTree("impl") {
                module("view")
                module("viewmodel")
            }
            module("spec")
        }
        moduleTree("session-catalog") {
            moduleTree("impl") {
                module("view")
                module("viewmodel")
            }
            module("spec")
        }
        moduleTree("session-delete") {
            moduleTree("impl") {
                module("view")
                module("viewmodel")
            }
            module("spec")
        }
        moduleTree("session-rename") {
            moduleTree("impl") {
                module("view")
                module("viewmodel")
            }
            module("spec")
        }
        moduleTree("session-settings") {
            moduleTree("impl") {
                module("view")
                module("viewmodel")
            }
            module("spec")
        }
        moduleTree("session-sidebar") {
            moduleTree("impl") {
                module("view")
            }
            module("spec")
        }
        moduleTree("session-tab-bar") {
            moduleTree("impl") {
                module("view")
            }
            module("spec")
        }
        moduleTree("session-title-settings") {
            moduleTree("impl") {
                module("view")
                module("viewmodel")
            }
            module("spec")
        }
        moduleTree("settings") {
            moduleTree("impl") {
                module("view")
                module("viewmodel")
            }
            module("spec")
        }
        moduleTree("suggest-subagent-task") {
            moduleTree("impl") {
                module("view")
                module("viewmodel")
            }
            module("spec")
        }
        moduleTree("usage-reset") {
            moduleTree("impl") {
                module("view")
                module("viewmodel")
            }
            module("spec")
        }
        moduleTree("working-directory") {
            moduleTree("impl") {
                module("view")
                module("viewmodel")
            }
            module("spec")
        }
    }
    moduleTree("impl") {
        module("application")
        module("rpc")
        module("session")
        module("view")
    }
    moduleTree("migration") {
        module("impl")
        module("spec")
    }
    moduleTree("settings") {
        moduleTree("impl") {
            module("filesystem")
        }
        moduleTree("spec") {
            module("persistence")
        }
    }
    moduleTree("spec") {
        module("application")
        module("session")
    }
    moduleTree("test-support") {
        module("rpc")
    }
    moduleTree("view") {
        module("components")
    }
}

moduleTree("rpc") {
    moduleTree("impl") {
        module("client")
        module("in-memory")
        module("krpc-utils-patch")
        module("server")
    }
    moduleTree("spec") {
        module("contract")
        module("models")
    }
}

moduleTree("mcp") {
    moduleTree("impl") {
        module("composition")
    }
    moduleTree("spec") {
        module("contract")
        module("stdio")
        module("streamable-http")
    }
}

moduleTree("openai") {
    moduleTree("impl") {
        module("account-usage")
        module("client")
        module("client-test")
        module("codex-cli-storage")
        module("model-catalog")
    }
    moduleTree("spec") {
        module("account-usage")
        module("client")
        module("client-test")
        module("codex-cli-storage")
        module("json-codec")
        module("model-catalog")
        module("models")
    }
}

moduleTree("agent-state") {
    moduleTree("impl") {
        module("state")
    }
    moduleTree("spec") {
        module("context-window")
        module("contract")
    }
    module("test")
}

moduleTree("agent-context") {
    moduleTree("impl") {
        module("agents-md-filesystem")
        module("prefix")
        module("prefix-render")
        module("skill-filesystem")
        module("skill-render")
    }
    moduleTree("spec") {
        module("agents-md")
        module("available-skill")
        module("contract")
        module("prefix")
        module("prompt-dsl")
        module("skill")
    }
}

moduleTree("agent-runtime") {
    moduleTree("impl") {
        module("composition")
        moduleTree("decorator") {
            module("compact")
            module("steer")
            module("tool")
        }
    }
    moduleTree("spec") {
        module("contract")
        moduleTree("decorator") {
            module("compact")
            module("steer")
            module("tool")
        }
    }
}

moduleTree("agent-session") {
    moduleTree("impl") {
        module("filesystem")
        module("in-memory")
    }
    moduleTree("spec") {
        module("contract")
    }
    module("test")
}

moduleTree("agent-storage") {
    moduleTree("impl") {
        module("filesystem")
        module("filesystem-layout")
        module("in-memory")
    }
    moduleTree("spec") {
        module("clean-models")
        module("contract")
        module("contract-ext")
    }
}

moduleTree("hook") {
    moduleTree("impl") {
        module("notification")
    }
    moduleTree("spec") {
        module("notification")
    }
}

moduleTree("tool") {
    moduleTree("impl") {
        module("apply-patch")
        module("builder")
        module("current-time")
        module("get-context-remaining")
        module("image-generation")
        module("multi-agent")
        module("plan")
        module("request-user-input")
        module("tool-search")
        module("unified-exec")
        module("view-image")
        module("web-run")
    }
    moduleTree("spec") {
        module("apply-patch")
        module("builder")
        module("contract")
        module("current-time")
        module("get-context-remaining")
        module("image-generation")
        module("multi-agent")
        module("plan")
        module("request-user-input")
        module("tool-search")
        module("unified-exec")
        module("view-image")
        module("web-run")
    }
}

moduleTree("utils") {
    moduleTree("coroutines") {
        module("spec")
    }
    moduleTree("external-url") {
        module("impl")
        module("spec")
    }
    moduleTree("filesystem-lease") {
        module("impl")
        module("spec")
    }
    moduleTree("images-codec") {
        module("impl")
        module("spec")
    }
    moduleTree("images") {
        module("spec")
    }
    moduleTree("kodex-home") {
        module("spec")
    }
    moduleTree("kotlinx-io-coroutines") {
        module("impl")
        module("spec")
    }
    moduleTree("kotlinx-io-serialization") {
        module("spec")
    }
    moduleTree("ktor-client-ext") {
        module("impl")
        module("spec")
    }
    moduleTree("logging") {
        module("impl")
        module("spec")
    }
    moduleTree("os-environment") {
        module("spec")
    }
    moduleTree("patch") {
        module("impl")
        module("spec")
    }
    moduleTree("process-client") {
        module("impl")
        module("spec")
    }
    moduleTree("read-write-mutex") {
        module("impl")
        module("spec")
    }
    moduleTree("rpc-exception") {
        module("spec")
    }
    moduleTree("search-index") {
        module("impl")
        module("spec")
    }
    moduleTree("shell-client") {
        module("impl")
        module("spec")
    }
    moduleTree("terminal-text") {
        module("spec")
    }
}
