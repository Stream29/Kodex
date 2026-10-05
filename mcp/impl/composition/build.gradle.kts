plugins {
    id("kodex.kmp-cli")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":mcp-spec-contract"))
            api(libs.kotlinx.coroutines.core)
            implementation(project(":mcp-spec-stdio"))
            implementation(project(":mcp-spec-streamable-http"))
            implementation(libs.mcp.kotlin.sdk.client)
            implementation(project(":utils-coroutines-spec"))
            implementation(project(":utils-logging-impl"))
            implementation(project(":utils-process-client-impl"))
            implementation(project(":utils-read-write-mutex-impl"))
            implementation(libs.ktor.server.cio)
            implementation(libs.ktor.server.core)
            implementation(libs.ktor.utils)
            implementation(libs.kotlin.logging)
            implementation(libs.kotlinx.schema.json)
            implementation(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(project(":openai-spec-json-codec"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.mock)
        }
        jvmTest.dependencies {
            implementation(libs.ktor.server.cio)
            implementation(libs.ktor.server.core)
        }
    }
}
