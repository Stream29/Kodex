plugins {
    id("kodex.kmp-host")
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":openai-spec-codex-cli-storage"))
            api(project(":utils-kotlinx-io-coroutines"))
            api(libs.kotlinx.io.core)
            api(libs.tomlkt)
            implementation(project(":openai-spec-json-codec"))
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
