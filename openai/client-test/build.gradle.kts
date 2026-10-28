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
                api(project(":openai-client"))
                api(project(":openai-models"))
                api(libs.kotlinx.coroutines.core)
            }
        }
        commonMain.dependencies {
            api(project(":openai-client"))
            api(project(":openai-models"))
            api(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
