plugins {
    id("kodex.kmp-spec-impl")
    id("kodex.krpc-utils-patch")
    id("kodex.kmp-cli")
}
kotlin {
sourceSets {
    named("specMain").dependencies { api(libs.kotlinx.serialization.json) }
    commonMain.dependencies {
        implementation(project(":app-impl-backend-daemon"))
        implementation(project(":rpc-impl-websocket"))
        api(libs.kotlinx.rpc.core)
        implementation(libs.kotlinx.rpc.krpc.server)
        implementation(libs.kotlinx.rpc.krpc.serialization.json)
        implementation(libs.ktor.server.core)
        implementation(libs.ktor.server.cio)
        implementation(libs.ktor.server.websockets)
    }
}
}
