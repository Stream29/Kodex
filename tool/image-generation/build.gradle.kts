plugins {
    id("kodex.kmp-spec-impl")
    id("kodex.kmp-host")
    id("kodex.kmp-tests")
}

kotlin {
    sourceSets {
        named("specMain") {
            dependencies {
                api(project(":openai-models"))
                api(project(":tool-image-generation-contract"))
                api(project(":openai-client"))
                api(project(":tool-core"))
                api(project(":utils-images"))
                api(project(":utils-kotlinx-io-coroutines-system"))
                api(project(":utils-images-codec"))
            }
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.coroutines.test)
            implementation(project(":openai-client-test"))
            implementation(project(":openai-json-codec"))
        }
    }
}
