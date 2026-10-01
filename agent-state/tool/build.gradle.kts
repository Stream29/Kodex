plugins {
    id("kodex.kmp-host")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":mcp-spec-contract"))
            api(project(":openai-spec-models"))
            api(project(":tool-impl-tool-search"))
            implementation(project(":tool-impl-apply-patch"))
            implementation(project(":tool-impl-current-time"))
            implementation(project(":tool-impl-image-generation"))
            implementation(project(":tool-impl-view-image"))
            implementation(project(":tool-impl-get-context-remaining"))
            implementation(project(":tool-impl-plan"))
            implementation(project(":tool-impl-request-user-input"))
            implementation(project(":tool-impl-multi-agent"))
            implementation(project(":tool-impl-unified-exec"))
            implementation(project(":tool-impl-web-run"))
            implementation(project(":openai-spec-json-codec"))
        }
    }
}
