plugins {
    id("kodex.kmp-host")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":agent-context-spec-prefix"))
            api(project(":agent-context-spec-available-skill"))
            api(project(":openai-spec-models"))
            implementation(project(":agent-context-spec-prompt-dsl"))
            implementation(libs.kotlinx.datetime)
        }
    }
}
