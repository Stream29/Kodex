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
            api(project(":tool-image-generation-contract"))
            api(project(":tool-request-user-input-contract"))
            api(project(":tool-multi-agent-contract"))
            api(project(":tool-tool-search-contract"))
            api(project(":tool-unified-exec-spec"))
            api(project(":tool-view-image-contract"))
            api(project(":utils-patch"))
        }
        commonTest.dependencies {
            implementation(project(":openai-spec-json-codec"))
        }
    }
}
