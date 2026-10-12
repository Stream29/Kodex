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
                api(project(":openai-spec-models"))
                api(libs.kotlinx.serialization.core)
                api(libs.kotlinx.schema.json)
            }
        }
        commonMain.dependencies {
            implementation(project(":utils-search-index-impl"))
            implementation(libs.kotlinx.coroutines.core)
        }
    }
}
