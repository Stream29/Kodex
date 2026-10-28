plugins {
    id("kodex.spec-api-module")
    id("kodex.kmp-spec-impl")
    id("kodex.kmp-frontend")
    alias(libs.plugins.kotlin.serialization)
    id("kodex.kmp-tests")
}

kotlin {
    sourceSets {
        named("specMain") {
            dependencies {
                api(libs.kotlinx.coroutines.core)
                api(project(":agent-storage-clean-models"))
                api(project(":openai-models"))
                api(libs.kotlinx.serialization.json)
            }
        }
        commonMain.dependencies {
            implementation(project(":openai-json-codec"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(project(":utils-read-write-mutex"))
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
