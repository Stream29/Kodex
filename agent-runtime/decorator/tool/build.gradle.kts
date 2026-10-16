plugins {
    id("kodex.kmp-spec-impl")
    id("kodex.kmp-host")
    id("kodex.kmp-tests")
}

kotlin {
    sourceSets {
        named("specMain") {
            dependencies {
                api(project(":agent-runtime-contract"))
            }
        }
        commonMain.dependencies {
            api(project(":agent-runtime-contract"))
            api(project(":tool-core"))
            api(project(":tool-tool-search"))
            api(libs.kotlin.logging)
            api(libs.kotlinx.coroutines.core)
            implementation(project(":utils-logging"))
        }
        commonTest.dependencies {
            implementation(project(":agent-state-core"))
            implementation(project(":agent-state-test"))
            implementation(project(":agent-storage-core"))
            implementation(project(":mcp"))
            implementation(project(":openai-client-test"))
            implementation(project(":openai-json-codec"))
            implementation(project(":tool-apply-patch"))
            implementation(project(":tool-plan"))
            implementation(project(":tool-unified-exec"))
            implementation(project(":utils-coroutines"))
            implementation(project(":utils-kotlinx-io-coroutines-system"))
            implementation(project(":utils-shell-client-system"))
            implementation(libs.kotlinx.io.core)
        }
    }
}
