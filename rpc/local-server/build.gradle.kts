plugins {
    id("kodex.kmp-spec-impl")
    id("kodex.krpc-utils-patch")
    id("kodex.kmp-cli")
    id("kodex.kmp-tests")
}

kotlin {
sourceSets {
    named("specMain").dependencies {
        api(libs.kotlinx.serialization.json)
        api(libs.kotlinx.rpc.core)
        api(libs.kotlinx.coroutines.core)
        api(libs.kotlinx.io.core)
    }
    commonMain.dependencies {
        implementation(project(":app-backend-daemon"))
        implementation(project(":rpc-websocket"))
        implementation(libs.kotlinx.rpc.krpc.server)
        implementation(libs.kotlinx.rpc.krpc.serialization.json)
        implementation(libs.ktor.server.core)
        implementation(libs.ktor.server.cio)
        implementation(libs.ktor.server.websockets)
    }
    commonTest.dependencies {
        implementation(project(":rpc-backend-control-client"))
        implementation(libs.ktor.client.core)
        implementation(libs.ktor.client.websockets)
    }
    macosArm64Test.dependencies { implementation(libs.ktor.client.darwin) }
    linuxTest.dependencies { implementation(libs.ktor.client.curl) }
    mingwX64Test.dependencies { implementation(libs.ktor.client.winhttp) }
}
}
