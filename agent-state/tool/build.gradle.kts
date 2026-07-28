plugins {
    id("codexlite.kmp-host")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":mcp-contract"))
            api(project(":openai-models"))
            api(project(":tool-impl-tool-search"))
            implementation(project(":tool-impl-apply-patch"))
            implementation(project(":tool-impl-current-time"))
            implementation(project(":tool-impl-image-generation"))
            implementation(project(":tool-impl-view-image"))
            implementation(project(":tool-spec-get-context-remaining"))
            implementation(project(":tool-spec-multi-agent"))
            implementation(project(":tool-spec-plan"))
            implementation(project(":tool-spec-request-user-input"))
            implementation(project(":tool-impl-unified-exec"))
            implementation(project(":tool-impl-web-run"))
        }
    }
}
