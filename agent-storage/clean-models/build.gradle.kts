plugins {
    id("kodex.kmp-spec-impl")
    id("kodex.kmp-frontend")
    alias(libs.plugins.kotlin.serialization)
    id("kodex.kmp-tests")
}

kotlin {
    sourceSets {
        named("specMain") {
            dependencies {
                api(libs.kotlinx.serialization.json)
                api(libs.kotlinx.schema.json)
                api(project(":openai-models"))
                api(project(":tool-image-generation-contract"))
                api(project(":tool-request-user-input"))
                api(project(":tool-multi-agent"))
                api(project(":tool-tool-search-contract"))
                api(project(":tool-unified-exec-contract"))
                api(project(":tool-view-image-contract"))
                api(project(":utils-patch"))
            }
        }
        commonTest.dependencies {
            implementation(project(":openai-json-codec"))
            implementation(project(":utils-patch"))
        }
        nativeTest.dependencies {
            implementation(project(":utils-kotlinx-io-coroutines-system"))
        }
    }
}
