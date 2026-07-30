plugins {
    id("kodex.kmp-host")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":mcp-contract"))
            api(project(":openai-models"))
            api(project(":tool-tool-search-impl"))
            implementation(project(":tool-apply-patch"))
            implementation(project(":tool-current-time"))
            implementation(project(":tool-image-generation-impl"))
            implementation(project(":tool-view-image-impl"))
            implementation(project(":tool-get-context-remaining"))
            implementation(project(":tool-multi-agent-impl"))
            implementation(project(":tool-plan"))
            implementation(project(":tool-request-user-input-impl"))
            implementation(project(":tool-unified-exec-impl"))
            implementation(project(":tool-web-run"))
            implementation(project(":openai-json-codec"))
        }
    }
}
