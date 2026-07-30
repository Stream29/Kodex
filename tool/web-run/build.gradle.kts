plugins {
    id("kodex.kmp-host")
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":openai-client-contract"))
            api(project(":openai-models"))
            api(project(":tool-contract"))
            api(project(":tool-tool-builder"))
            implementation(libs.kotlinx.schema.json)
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            implementation(project(":openai-client"))
            implementation(project(":openai-codex-cli-storage"))
            implementation(project(":openai-json-codec"))
            implementation(project(":utils-os-environment"))
        }
    }
}
