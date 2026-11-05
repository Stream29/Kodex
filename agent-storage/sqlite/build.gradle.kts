plugins {
    id("kodex.spec-api-module")
    id("kodex.kmp-spec-impl")
    id("kodex.kmp-host")
    alias(libs.plugins.kotlin.serialization)
    id("kodex.kmp-tests")
}

kotlin {
    sourceSets {
        named("specMain").dependencies {
            api(project(":agent-storage-core"))
            api(project(":utils-sqlite"))
            api(libs.kotlinx.coroutines.core)
        }
        commonMain.dependencies {
            implementation(project(":openai-json-codec"))
            implementation(libs.kotlinx.serialization.json)
        }
    }
}
