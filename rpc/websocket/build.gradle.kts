plugins {
    id("kodex.spec-api-module")
    id("kodex.kmp-spec-impl")
    id("kodex.krpc-utils-patch")
    id("kodex.kmp-frontend")
}

kotlin {
sourceSets.named("specMain").dependencies {
    api(libs.kotlinx.rpc.krpc.core)
    api(libs.ktor.websockets)
}
sourceSets.commonMain.dependencies {
    implementation(libs.kotlinx.coroutines.core)
}
}
