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
includeModuleTree("llm-provider")
includeModuleTree("openai")
includeModuleTree("agent-state")
includeModuleTree("agent-storage")
includeModuleTree("tool")
includeModuleTree("utils")
