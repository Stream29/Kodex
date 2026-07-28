plugins {
    id("codexlite.kmp-host")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":mcp-contract"))
            api(project(":openai-models"))
            api(project(":tool-tool-search"))
            implementation(project(":tool-apply-patch"))
            implementation(project(":tool-current-time"))
            implementation(project(":tool-image-generation"))
            implementation(project(":tool-view-image"))
            implementation(project(":tool-get-context-remaining"))
            implementation(project(":tool-multi-agent"))
            implementation(project(":tool-plan"))
            implementation(project(":tool-request-user-input"))
            implementation(project(":tool-unified-exec"))
            implementation(project(":tool-web-run"))
        }
    }
}
