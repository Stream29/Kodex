plugins {
    id("kodex.spec-api-module")
    id("kodex.kmp-spec-impl")
    id("kodex.kmp-host")
    alias(libs.plugins.kotlin.serialization)
    id("kodex.kmp-tests")
}

kotlin {
    sourceSets {
        named("specMain") {
            dependencies {
                api(project(":tool-tool-search-contract"))
                api(project(":openai-models"))
                api(libs.kotlinx.schema.json)
            }
        }
        commonMain.dependencies {
            implementation(project(":utils-search-index"))
            implementation(libs.kotlinx.coroutines.core)
        }
    }
}
