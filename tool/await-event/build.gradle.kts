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
                implementation(libs.kotlinx.serialization.json)
                implementation(project(":tool-intent-name"))
                api(project(":openai-models"))
                api(libs.kotlinx.schema.json)
            }
        }
        commonTest.dependencies {
            implementation(project(":openai-json-codec"))
        }
    }
}
