plugins {
    id("codexlite.kmp-host")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":agent-context-contract"))
            api(project(":openai-models"))
            implementation(project(":agent-context-prompt-dsl"))
        }
    }
}
