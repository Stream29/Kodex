plugins {
    id("codexlite.kmp-host")
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":agent-storage-contract"))
            api(libs.kotlinx.serialization.json)
            implementation(project(":openai-json-codec"))
            implementation(project(":utils-kotlinx-io-coroutines"))
        }
    }
}
