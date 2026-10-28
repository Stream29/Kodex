plugins {
    id("kodex.spec-api-module")
    id("kodex.kmp-spec-impl")
    id("kodex.krpc-utils-patch")
    id("kodex.kmp-cli")
    alias(libs.plugins.kotlin.serialization)
    id("kodex.kmp-tests")
}

kotlin {
    sourceSets {
        named("specMain").dependencies {
            api(project(":rpc-connection"))
            api(libs.kotlinx.rpc.core)
            api(libs.kotlinx.coroutines.core)
            api(project(":app-backend-daemon"))
            api(libs.kotlinx.io.core)
        }
        commonMain.dependencies {
            implementation(project(":rpc-websocket"))
            api(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.rpc.krpc.client)
            implementation(libs.kotlinx.rpc.krpc.serialization.json)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.websockets)
        }
        macosArm64Main.dependencies { implementation(libs.ktor.client.darwin) }
        linuxMain.dependencies { implementation(libs.ktor.client.curl) }
        mingwX64Main.dependencies { implementation(libs.ktor.client.winhttp) }
        commonTest.dependencies { implementation(libs.kotlinx.coroutines.test) }
    }
}
