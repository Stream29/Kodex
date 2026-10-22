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
                api(libs.kotlinx.coroutines.core)
                api(project(":openai-client"))
            }
        }
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(project(":openai-client-test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
