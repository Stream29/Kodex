plugins {
    id("kodex.kmp-host")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":mcp-spec-streamable-http"))
            api(project(":mcp-spec-contract"))
            api(libs.kotlinx.coroutines.core)
            api(libs.ktor.client.core)
            api(libs.mcp.kotlin.sdk.client)
            implementation(project(":utils-ktor-client-ext-impl"))
            implementation(libs.ktor.sse)
        }
        commonTest.dependencies {
            implementation(libs.ktor.client.mock)
        }
    }
}
