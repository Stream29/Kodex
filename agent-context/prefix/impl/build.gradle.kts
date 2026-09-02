plugins {
    id("kodex.kmp-host")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":agent-context-contract"))
            api(project(":agent-context-prefix-contract"))
            api(project(":openai-models"))
            api(libs.kotlinx.coroutines.core)
            api(libs.kotlinx.io.core)
            implementation(project(":agent-context-prefix-agents-md-filesystem"))
            implementation(project(":agent-context-skill-filesystem"))
            implementation(project(":utils-os-environment"))
        }
        commonTest.dependencies {
            implementation(project(":utils-kotlinx-io-coroutines"))
        }
    }
}
