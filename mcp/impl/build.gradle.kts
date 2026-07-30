plugins {
    id("kodex.kmp-cli")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":mcp-contract"))
            implementation(project(":mcp-stdio"))
            implementation(project(":mcp-streamable-http"))
            implementation(libs.mcp.kotlin.sdk.client)
            implementation(project(":utils-coroutines"))
            implementation(project(":utils-process-client"))
            implementation(libs.kotlin.logging)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.schema.json)
            implementation(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(project(":openai-json-codec"))
            implementation(libs.kotlinx.coroutines.test)
        }
        jvmTest.dependencies {
            implementation(libs.ktor.server.cio)
            implementation(libs.ktor.server.core)
        }
    }
}
