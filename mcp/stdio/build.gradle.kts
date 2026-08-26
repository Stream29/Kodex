plugins {
    id("kodex.kmp-host")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":mcp-contract"))
            api(libs.mcp.kotlin.sdk.client)
            api(project(":utils-process-client"))
            implementation(libs.kotlinx.io.core)
        }
        jvmTest.dependencies {
            implementation(project(":mcp-impl"))
            implementation(libs.kotlinx.serialization.json)
        }
    }
}
