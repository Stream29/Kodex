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
                api(libs.kotlinx.serialization.core)
                api(project(":openai-spec-models"))
                api(libs.kotlinx.schema.json)
            }
        }
        commonTest.dependencies {
            implementation(project(":openai-spec-json-codec"))
        }
    }
}
