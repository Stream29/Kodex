plugins {
    id("kodex.kmp-host")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":mcp-spec-contract"))
            api(libs.ktor.client.core)
            api(libs.mcp.kotlin.sdk.client)
            api(libs.kotlinx.coroutines.core)
        }
    }
}
