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

include(":auth")
include(":codex-compatibility")
include(":llm-provider")
include(":llm-provider:api")
include(":llm-provider:models")
include(":tool:contract")
include(":tool:web-search")
include(":utils")
include(":utils:read-write-mutex")
