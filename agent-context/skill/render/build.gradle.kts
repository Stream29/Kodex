plugins {
    id("codexlite.kmp-host")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":agent-context-skill-contract"))
            implementation(project(":agent-context-prompt-dsl"))
        }
    }
}
