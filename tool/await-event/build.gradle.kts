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
                api(libs.kotlinx.serialization.core)
                api(project(":openai-models"))
                api(libs.kotlinx.schema.json)
            }
        }
        commonTest.dependencies {
            implementation(project(":openai-json-codec"))
        }
    }
}
