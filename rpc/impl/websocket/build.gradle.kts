plugins {
    id("kodex.krpc-utils-patch")
    id("kodex.kmp-cli")
}
kotlin {
sourceSets.commonMain.dependencies {
    api(libs.kotlinx.rpc.krpc.core)
    api(libs.ktor.websockets)
    implementation(libs.kotlinx.coroutines.core)
}
}
