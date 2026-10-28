plugins {
    id("kodex.spec-api-module")
    id("kodex.kmp-spec-impl")
    id("kodex.kmp-host")
    alias(libs.plugins.kotlin.serialization)
    id("kodex.kmp-tests")
}

kotlin {
    sourceSets {
        named("specMain") {
            dependencies {
                api(libs.kotlinx.coroutines.core)
                api(libs.kotlinx.io.core)
                api(project(":openai-models"))
            }
        }
        commonMain.dependencies {
            api(libs.kotlinx.coroutines.core)
            api(project(":openai-models"))
            implementation(project(":openai-json-codec"))
            implementation(project(":utils-ktor-client-ext"))
            implementation(project(":utils-os-environment"))
            implementation(libs.kotlin.logging)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.kotlinx.json)
            implementation(libs.ktor.sse)
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
            implementation(project(":openai-client-test"))
            implementation(project(":openai-json-codec"))
            implementation(libs.ktor.client.mock)
        }
        nativeTest.dependencies {
            implementation(libs.ktor.client.cio)
            implementation(libs.ktor.server.cio)
        }
    }
}

val nativeNetworkVersion = libs.versions.ktor.network.native.get()
configurations.configureEach {
    if (name.contains("Test", ignoreCase = true)) {
        resolutionStrategy.force(
            "io.ktor:ktor-network-linuxx64:$nativeNetworkVersion",
            "io.ktor:ktor-network-linuxarm64:$nativeNetworkVersion",
            "io.ktor:ktor-network-macosarm64:$nativeNetworkVersion",
            "io.ktor:ktor-network-mingwx64:$nativeNetworkVersion",
        )
    }
}
