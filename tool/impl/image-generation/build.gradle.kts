plugins {
    id("kodex.kmp-host")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":tool-spec-image-generation"))
            api(project(":openai-spec-client"))
            api(project(":openai-spec-models"))
            api(project(":tool-spec-contract"))
            api(project(":utils-images-spec"))
            api(project(":utils-kotlinx-io-coroutines-impl"))
            api(libs.kotlinx.schema.json)
            implementation(project(":utils-images-codec-impl"))
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.coroutines.test)
            implementation(project(":openai-impl-client-test"))
            implementation(project(":openai-spec-json-codec"))
        }
    }
}
