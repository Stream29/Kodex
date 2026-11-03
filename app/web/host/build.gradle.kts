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
            api(project(":rpc-models"))
            api(libs.kotlinx.coroutines.core)
            api(libs.kotlinx.io.core)
        }
        commonMain.dependencies {
            implementation(project(":utils-kauri"))
            implementation(project(":utils-external-url"))
            implementation(project(":utils-process-client"))
            implementation(project(":utils-process-client-system"))
            implementation(project(":utils-kotlinx-io-coroutines"))
            implementation(project(":utils-os-environment"))
            implementation(project(":rpc-backend-control-client"))
            implementation(project(":rpc-local"))
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.ktor.server.core)
            implementation(libs.ktor.server.cio)
        }
        commonTest.dependencies { implementation(libs.kotlinx.coroutines.test) }
        nativeTest.dependencies {
            implementation("io.ktor:ktor-network:${libs.versions.ktor.asProvider().get()}")
            implementation(project(":app-backend-host"))
            implementation(project(":app-rpc"))
            implementation(project(":rpc-client"))
            implementation(project(":openai-client-test"))
            implementation(project(":utils-kotlinx-io-coroutines-system"))
            implementation(project(":utils-os-environment"))
            implementation(project(":utils-shell-client-system"))
        }
    }
}
