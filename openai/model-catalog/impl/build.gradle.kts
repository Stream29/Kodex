plugins {
    id("codexlite.kmp-host")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":openai-client-contract"))
            api(project(":openai-codex-cli-storage"))
            api(project(":openai-model-catalog-contract"))
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(project(":openai-client"))
            implementation(project(":openai-client-test"))
            implementation(project(":openai-json-codec"))
            implementation(project(":utils-kotlinx-io-coroutines"))
            implementation(project(":utils-os-environment"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
