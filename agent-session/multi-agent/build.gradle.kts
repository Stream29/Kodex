plugins {
    id("codexlite.kmp-host")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":agent-session-contract"))
        }
        commonTest.dependencies {
            implementation(project(":agent-session-in-memory"))
            implementation(project(":agent-session-test"))
        }
    }
}
