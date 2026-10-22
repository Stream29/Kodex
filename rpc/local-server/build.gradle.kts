plugins {
    id("kodex.kmp-spec-impl")
    id("kodex.krpc-utils-patch")
    id("kodex.kmp-cli")
}

kotlin {
sourceSets {
    named("specMain").dependencies {
        api(libs.kotlinx.serialization.json)
        api(libs.kotlinx.rpc.core)
        api(libs.kotlinx.coroutines.core)
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
}
}
