plugins {
    id("kodex.kmp-spec-impl")
    id("kodex.kmp-host")
    alias(libs.plugins.kotlin.serialization)
    id("kodex.kmp-tests")
}

kotlin {
    sourceSets {
        named("specMain") {
            dependencies {
                api(libs.kotlinx.coroutines.core)
                api(libs.kotlinx.serialization.core)
            }
        }
        commonMain.dependencies {
            api(project(":openai-client"))
            implementation(project(":openai-models"))
            implementation(libs.kotlinx.coroutines.core)
        }
    }
}
