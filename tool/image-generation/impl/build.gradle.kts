plugins {
    id("kodex.kmp-host")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":tool-image-generation-contract"))
            api(project(":openai-spec-client"))
            api(project(":openai-spec-models"))
            api(project(":tool-contract"))
            api(project(":utils-images"))
            api(project(":utils-kotlinx-io-coroutines"))
            api(libs.kotlinx.schema.json)
            implementation(project(":utils-images-codec"))
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.coroutines.test)
            implementation(project(":openai-impl-client-test"))
            implementation(project(":openai-spec-json-codec"))
        }
    }
}
