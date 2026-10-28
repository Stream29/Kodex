import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

plugins {
    id("kodex.spec-api-module")
    id("kodex.kmp-spec-impl")
    id("kodex.krpc-utils-patch")
    id("kodex.kmp-cli")
}

kotlin {
    targets.withType<KotlinNativeTarget>().configureEach {
        binaries.executable {
            baseName = "kodex-web-fixture"
            entryPoint = "io.github.stream29.kodex.app.webbackendfixture.main"
        }
        binaries.executable("browser") {
            baseName = "kodex-product-browser-fixture"
            entryPoint = "io.github.stream29.kodex.app.webbackendfixture.productMain"
        }
    }
    sourceSets.commonMain.dependencies {
        implementation(project(":app-backend-migration"))
        implementation(project(":app-backend-host"))
        implementation(project(":rpc-backend-control-client"))
        implementation(project(":app-rpc-test-support"))
        implementation(project(":rpc-server"))
        implementation(project(":rpc-websocket"))
        implementation(project(":openai-client-test"))
        implementation(project(":utils-os-environment"))
        implementation(libs.ktor.server.cio)
        implementation(libs.ktor.server.websockets)
        implementation(libs.kotlinx.rpc.krpc.server)
        implementation(libs.kotlinx.rpc.krpc.serialization.json)
        implementation(libs.kotlinx.serialization.json)
    }
}
