plugins {
    id("kodex.kmp-host")
    id("kodex.kmp-tests")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":tool-spec-image-generation"))
            api(project(":openai-client"))
            api(project(":openai-spec-models"))
            api(project(":tool-spec-contract"))
            api(project(":utils-images-spec"))
            api(project(":utils-kotlinx-io-coroutines-impl"))
            implementation(project(":utils-images-codec-impl"))
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.coroutines.test)
            implementation(project(":openai-client-test"))
            implementation(project(":openai-spec-json-codec"))
        }
    }
}
