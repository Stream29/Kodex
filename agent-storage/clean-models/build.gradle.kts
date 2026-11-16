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
                api(libs.kotlinx.io.core)
                api(libs.kotlinx.serialization.json)
                api(libs.kotlinx.schema.json)
                api(project(":openai-json-codec"))
                api(project(":openai-models"))
                api(project(":utils-kotlinx-io-serialization"))
                api(project(":tool-await-event"))
                api(project(":tool-read-media-contract"))
                api(project(":tool-request-user-input"))
                api(project(":tool-unified-exec-contract"))
                api(project(":utils-patch"))
            }
        }
        commonTest.dependencies {
            implementation(project(":openai-json-codec"))
            implementation(project(":utils-patch"))
        }
        nativeTest.dependencies {
            implementation(project(":utils-kotlinx-io-coroutines"))
        }
    }
}
