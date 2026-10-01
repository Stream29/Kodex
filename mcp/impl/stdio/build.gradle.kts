plugins {
    id("kodex.kmp-host")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":mcp-spec-stdio"))
            api(project(":mcp-spec-contract"))
            api(libs.mcp.kotlin.sdk.client)
            api(project(":utils-process-client-impl"))
            implementation(libs.kotlinx.io.core)
        }
        jvmTest.dependencies {
            implementation(project(":mcp-impl-composition"))
            implementation(libs.kotlinx.serialization.json)
        }
    }
}
