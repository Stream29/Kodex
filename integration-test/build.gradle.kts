plugins {
    id("codexlite.kmp-host")
}

kotlin {
    sourceSets {
        commonTest.dependencies {
            implementation(project(":agent-context-collaboration-render"))
            implementation(project(":agent-runtime-compact"))
            implementation(project(":agent-runtime-plan"))
            implementation(project(":agent-runtime-tool"))
            implementation(project(":agent-state-impl"))
            implementation(project(":agent-storage-contract"))
            implementation(project(":agent-storage-in-memory"))
            implementation(project(":openai-client"))
            implementation(project(":openai-client-contract"))
            implementation(project(":openai-client-test"))
            implementation(project(":openai-codex-cli-storage"))
            implementation(project(":openai-json-codec"))
            implementation(project(":tool-impl-apply-patch"))
            implementation(project(":tool-impl-image-generation"))
            implementation(project(":tool-impl-plan"))
            implementation(project(":tool-impl-view-image"))
            implementation(project(":utils-os-environment"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
