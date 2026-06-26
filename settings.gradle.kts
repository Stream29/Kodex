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

include(":llm-provider")
include(":llm-provider:api")
include(":openai:models")
include(":openai:client-contract")
include(":openai:client")
include(":openai:client-test")
include(":openai:codex-cli-storage")
include(":tool:contract")
include(":tool:apply-patch")
include(":tool:image-generation")
include(":tool:tool-search")
include(":tool:view-image")
include(":tool:web-search")
include(":utils")
include(":utils:host-test-support")
include(":utils:images")
include(":utils:images-codec")
include(":utils:kotlinx-io-coroutines")
include(":utils:patch")
include(":utils:read-write-mutex")
include(":utils:search-index")
