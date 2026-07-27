pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

@Suppress("UnstableApiUsage")
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_PROJECT)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "CodexLite"

includeBuild("Mosaic")
includeBuild("KotlinMcpSdk") {
    name = "kotlin-mcp-sdk"
}
includeBuild("TomlKt") {
    dependencySubstitution {
        substitute(module("dev.eav.tomlkt:tomlkt")).using(project(":core"))
    }
}

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
includeModuleTree("cli")
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
