plugins {
    id("kodex.spec-api-module")
    id("kodex.kmp-spec-impl")
    id("kodex.kmp-host")
    id("kodex.kmp-tests")
}

kotlin {
    sourceSets {
        named("specMain").dependencies {
            api(project(":app-rpc-protocol"))
            api(libs.kotlinx.coroutines.core)
            api(libs.kotlinx.io.core)
        }
        commonMain.dependencies {
            implementation(project(":utils-kauri"))
            implementation(project(":utils-external-url"))
            implementation(project(":rpc-backend-control-client"))
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.ktor.server.core)
            implementation(libs.ktor.server.cio)
        }
        nativeTest.dependencies {
            implementation("io.ktor:ktor-network:${libs.versions.ktor.asProvider().get()}")
        }
    }
}
