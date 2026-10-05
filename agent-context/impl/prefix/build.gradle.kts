plugins {
    id("kodex.kmp-host")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":agent-context-spec-contract"))
            api(project(":agent-context-spec-prefix"))
            api(project(":openai-spec-models"))
            api(libs.kotlinx.coroutines.core)
            api(libs.kotlinx.io.core)
            implementation(project(":agent-context-impl-agents-md-filesystem"))
            implementation(project(":agent-context-impl-skill-filesystem"))
            implementation(project(":utils-os-environment-spec"))
        }
        commonTest.dependencies {
            implementation(project(":utils-kotlinx-io-coroutines-impl"))
        }
    }
}
