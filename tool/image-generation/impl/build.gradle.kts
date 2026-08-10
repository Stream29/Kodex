plugins {
    id("kodex.kmp-host")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":tool-image-generation-contract"))
            api(project(":openai-client-contract"))
            api(project(":openai-models"))
            api(project(":tool-contract"))
            api(project(":utils-images"))
            api(project(":utils-kotlinx-io-coroutines"))
            api(libs.kotlinx.schema.json)
            implementation(project(":utils-images-codec"))
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.coroutines.test)
            implementation(project(":openai-client"))
            implementation(project(":openai-client-test"))
            implementation(project(":openai-codex-cli-storage"))
            implementation(project(":openai-json-codec"))
            implementation(project(":utils-os-environment"))
        }
    }
}
