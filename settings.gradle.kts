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
include(":openai:json-codec")
include(":openai:models")
include(":openai:client-contract")
include(":openai:client")
include(":openai:client-test")
include(":openai:codex-cli-storage")
include(":tool:contract")
include(":tool:tool-builder")
include(":tool:impl:apply-patch")
include(":tool:impl:image-generation")
include(":tool:impl:view-image")
include(":tool:tool-search")
include(":utils")
include(":utils:images")
include(":utils:images-codec")
include(":utils:kotlinx-io-coroutines")
include(":utils:ktor-client-ext")
include(":utils:os-environment")
include(":utils:patch")
include(":utils:read-write-mutex")
include(":utils:search-index")
