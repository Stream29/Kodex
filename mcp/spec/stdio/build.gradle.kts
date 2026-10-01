plugins {
    id("kodex.kmp-host")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":mcp-spec-contract"))
            api(libs.mcp.kotlin.sdk.client)
            api(project(":utils-process-client-spec"))
        }
    }
}
