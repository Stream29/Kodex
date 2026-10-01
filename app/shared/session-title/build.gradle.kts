plugins {
    id("kodex.kmp-host")
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":agent-state-contract"))
            api(project(":openai-spec-client"))
            api(project(":openai-spec-models"))
            api(libs.kotlinx.coroutines.core)
            implementation(project(":openai-spec-json-codec"))
            implementation(libs.kotlinx.schema.json)
            implementation(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(project(":agent-session-in-memory"))
            implementation(project(":agent-session-test"))
            implementation(project(":agent-storage-spec-contract"))
            implementation(project(":agent-storage-spec-contract-ext"))
            implementation(project(":utils-coroutines-spec"))
            implementation(project(":openai-impl-client-test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
