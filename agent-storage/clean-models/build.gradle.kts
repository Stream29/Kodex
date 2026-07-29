plugins {
    id("codexlite.kmp-host")
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.serialization.json)
            api(libs.kotlinx.schema.json)
            api(project(":openai-models"))
            api(project(":tool-image-generation-contract"))
            api(project(":tool-multi-agent-contract"))
            api(project(":tool-request-user-input-contract"))
            api(project(":tool-tool-search-contract"))
            api(project(":tool-unified-exec-contract"))
            api(project(":tool-view-image-contract"))
            api(project(":utils-patch"))
        }
    }
}
