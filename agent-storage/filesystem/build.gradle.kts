plugins {
    id("kodex.kmp-host")
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":agent-storage-contract"))
            api(project(":utils-kotlinx-io-coroutines"))
            api(libs.kotlinx.serialization.json)
            implementation(project(":openai-json-codec"))
        }
    }
}
