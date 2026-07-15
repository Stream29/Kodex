plugins {
    id("codexlite.kmp-host")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":agent-context-prefix-contract"))
            api(project(":openai-models"))
            implementation(project(":agent-context-prompt-dsl"))
            implementation(project(":agent-context-skill-render"))
        }
    }
}
