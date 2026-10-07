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

fun includeModuleDir(path: String) {
    val projectPath = ":${path.replace('/', '-')}"
    include(projectPath)
    project(projectPath).projectDir = file(path)
}

fun includeModuleTree(rootPath: String) {
    val root = file(rootPath)
    includeModuleDir(rootPath)
    root.walkTopDown()
        .onEnter { it.name != "build" }
        .filter { it != root && it.resolve("build.gradle.kts").isFile }
        .map { it.relativeTo(rootDir).invariantSeparatorsPath }
        .sorted()
        .forEach(::includeModuleDir)
}

includeModuleTree("integration-test")
includeModuleTree("app")
includeModuleTree("rpc")
includeModuleTree("mcp")
includeModuleTree("openai")
includeModuleTree("agent-state")
includeModuleTree("agent-context")
includeModuleTree("agent-runtime")
includeModuleTree("agent-session")
includeModuleTree("agent-storage")
includeModuleTree("hook")
includeModuleTree("tool")
includeModuleTree("utils")
