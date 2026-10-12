plugins {
    id("kodex.kmp-spec-impl")
    id("kodex.kmp-host")
    id("kodex.kmp-tests")
}

kotlin {
    sourceSets {
        // The process-owned transport is implementation; its port is external.
        commonMain.dependencies {
            api(project(":mcp-spec-contract"))
            api(libs.mcp.kotlin.sdk.client)
            api(project(":utils-process-client-spec"))
            implementation(libs.kotlinx.io.core)
        }
        commonTest.dependencies {
            implementation(project(":utils-process-client-impl"))
        }
        jvmTest.dependencies {
            implementation(project(":mcp-impl-composition"))
            implementation(libs.kotlinx.serialization.json)
        }
    }
}
