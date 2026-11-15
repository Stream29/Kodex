plugins {
    id("kodex.spec-api-module")
    id("kodex.kmp-spec-impl")
    id("kodex.kmp-frontend")
    alias(libs.plugins.kotlin.serialization)
    id("kodex.kmp-tests")
}

kotlin {
    sourceSets {
        named("specMain").dependencies {
            api(project(":openai-models"))
            api(project(":utils-kotlinx-io-serialization"))
            api(libs.kotlinx.coroutines.core)
            api(libs.kotlinx.io.core)
            api(libs.kotlinx.schema.json)
            api(libs.kotlinx.serialization.core)
            api(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.serialization.json)
        }
    }
}
