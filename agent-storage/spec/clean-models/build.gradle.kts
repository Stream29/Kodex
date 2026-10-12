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
                api(libs.kotlinx.serialization.json)
                api(libs.kotlinx.schema.json)
                api(project(":openai-spec-models"))
                api(project(":tool-spec-image-generation"))
                api(project(":tool-request-user-input"))
                api(project(":tool-multi-agent"))
                api(project(":tool-tool-search"))
                api(project(":tool-spec-unified-exec"))
                api(project(":tool-spec-view-image"))
                api(project(":utils-patch-spec"))
            }
        }
        commonTest.dependencies {
            implementation(project(":openai-spec-json-codec"))
            implementation(project(":utils-patch-impl"))
            implementation(project(":utils-kotlinx-io-coroutines-impl"))
        }
    }
}
