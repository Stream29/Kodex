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
                api(project(":agent-storage-clean-models"))
                api(project(":openai-models"))
                api(libs.kotlinx.serialization.core)
                api(libs.kotlinx.serialization.json)
            }
        }
        commonMain.dependencies {
            implementation(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
