plugins {
    id("codexlite.kmp-host")
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.coroutines.core)
            api(project(":openai:client-contract"))
            api(project(":openai:models"))
            implementation(project(":openai:json-codec"))
            implementation(project(":utils:ktor-client-ext"))
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.kotlinx.json)
            implementation(libs.ktor.sse)
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
            implementation(project(":openai:codex-cli-storage"))
            implementation(project(":openai:json-codec"))
            implementation(project(":utils:os-environment"))
        }
    }
}
