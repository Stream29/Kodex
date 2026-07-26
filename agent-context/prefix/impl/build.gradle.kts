plugins {
    id("codexlite.kmp-host")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":agent-context-prefix-contract"))
            api(project(":agent-context-skill-contract"))
            api(libs.kotlinx.coroutines.core)
            implementation(project(":agent-context-prefix-agents-md-filesystem"))
            implementation(project(":agent-context-skill-filesystem"))
            implementation(project(":utils-os-environment"))
        }
        commonTest.dependencies {
            implementation(project(":utils-kotlinx-io-coroutines"))
        }
    }
}
