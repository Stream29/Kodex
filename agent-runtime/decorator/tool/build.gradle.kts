plugins {
    id("kodex.kmp-spec-impl")
    id("kodex.kmp-host")
    id("kodex.kmp-tests")
}

kotlin {
    sourceSets {
        named("specMain") {
            dependencies {
                api(project(":agent-runtime-spec-contract"))
            }
        }
        commonMain.dependencies {
            api(project(":agent-runtime-spec-contract"))
            api(project(":tool-spec-contract"))
            api(project(":tool-tool-search"))
            api(libs.kotlin.logging)
            api(libs.kotlinx.coroutines.core)
            implementation(project(":utils-logging-impl"))
        }
        commonTest.dependencies {
            implementation(project(":agent-state-impl-state"))
            implementation(project(":agent-state-test"))
            implementation(project(":agent-storage-core"))
            implementation(project(":mcp-spec-contract"))
            implementation(project(":openai-client-test"))
            implementation(project(":openai-spec-json-codec"))
            implementation(project(":tool-apply-patch"))
            implementation(project(":tool-plan"))
            implementation(project(":tool-impl-unified-exec"))
            implementation(project(":utils-coroutines-spec"))
            implementation(project(":utils-kotlinx-io-coroutines-impl"))
            implementation(project(":utils-shell-client-impl"))
            implementation(libs.kotlinx.io.core)
        }
    }
}
