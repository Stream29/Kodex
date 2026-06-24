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
include(":tool:apply-patch")
include(":tool:tool-search")
include(":tool:web-search")
include(":utils")
include(":utils:kotlinx-io-coroutines")
include(":utils:patch")
include(":utils:read-write-mutex")
include(":utils:search-index")
