plugins {
    id("codexlite.kmp-host")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":agent-context-prefix-contract"))
            api(project(":agent-state-impl"))
            api(project(":tool-impl-tool-search"))
            api(project(":utils-coroutines"))
        }
    }
}
