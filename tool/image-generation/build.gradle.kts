plugins {
    id("kodex.spec-api-module")
    id("kodex.kmp-spec-impl")
    id("kodex.kmp-host")
    id("kodex.kmp-tests")
}

kotlin {
    sourceSets {
        named("specMain") {
            dependencies {
                api(project(":openai-models"))
                api(project(":rpc-models"))
                api(project(":openai-client"))
                api(project(":utils-images"))
                api(project(":utils-kotlinx-io-coroutines"))
            }
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            implementation(project(":openai-client-test"))
        }
    }
}
