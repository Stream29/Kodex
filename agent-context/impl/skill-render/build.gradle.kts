plugins {
    id("kodex.kmp-host")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":agent-context-spec-skill"))
            implementation(project(":agent-context-spec-prompt-dsl"))
        }
        commonTest.dependencies {
            implementation(project(":agent-context-spec-available-skill"))
        }
    }
}
