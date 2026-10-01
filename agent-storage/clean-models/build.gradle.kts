plugins {
    id("kodex.kmp-host")
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.serialization.json)
            api(libs.kotlinx.schema.json)
            api(project(":openai-spec-models"))
            api(project(":tool-spec-image-generation"))
            api(project(":tool-spec-request-user-input"))
            api(project(":tool-spec-multi-agent"))
            api(project(":tool-spec-tool-search"))
            api(project(":tool-spec-unified-exec"))
            api(project(":tool-spec-view-image"))
            api(project(":utils-patch-spec"))
        }
        commonTest.dependencies {
            implementation(project(":openai-spec-json-codec"))
            implementation(project(":utils-patch-impl"))
        }
    }
}
