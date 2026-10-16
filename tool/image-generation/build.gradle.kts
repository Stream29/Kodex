plugins {
    id("kodex.kmp-spec-impl")
    id("kodex.kmp-host")
    id("kodex.kmp-tests")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":tool-image-generation-contract"))
            api(project(":openai-client"))
            api(project(":openai-models"))
            api(project(":tool-core"))
            api(project(":utils-images"))
            api(project(":utils-kotlinx-io-coroutines-system"))
            implementation(project(":utils-images-codec"))
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.coroutines.test)
            implementation(project(":openai-client-test"))
            implementation(project(":openai-json-codec"))
        }
    }
}
