plugins {
    id("kodex.kmp-host")
    alias(libs.plugins.kotlin.serialization)
    id("kodex.kmp-tests")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.coroutines.core)
            api(project(":openai-spec-client"))
            api(project(":openai-spec-models"))
            implementation(project(":openai-spec-json-codec"))
            implementation(project(":utils-ktor-client-ext-impl"))
            implementation(project(":utils-os-environment-spec"))
            implementation(libs.kotlin.logging)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.kotlinx.json)
            implementation(libs.ktor.sse)
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
            implementation(project(":openai-impl-client-test"))
            implementation(project(":openai-spec-json-codec"))
            implementation(libs.ktor.client.mock)
        }
    }
}
