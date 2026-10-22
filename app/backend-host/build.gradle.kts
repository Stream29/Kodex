plugins {
    id("kodex.kmp-spec-impl")
    id("kodex.krpc-utils-patch")
    id("kodex.kmp-cli")
    alias(libs.plugins.kotlin.serialization)
    id("kodex.kmp-tests")
}

kotlin {
    sourceSets {
        named("specMain").dependencies {
            api(project(":app-backend-daemon"))
            api(project(":openai-client"))
            api(project(":utils-os-environment"))
            api(libs.kotlinx.io.core)
        }
        commonMain.dependencies {
            implementation(project(":rpc-local-server"))
            implementation(project(":rpc-backend-control-client"))
            implementation(project(":rpc-server"))
            implementation(project(":app-migration"))
            implementation(project(":app-settings-filesystem"))
            implementation(project(":hook-notification"))
            implementation(project(":utils-kotlinx-io-coroutines-system"))
            implementation(project(":utils-logging-backend-file"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlin.logging)
        }
        commonTest.dependencies {
            implementation(project(":rpc-local"))
            implementation(project(":rpc-backend-control-client"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(project(":openai-client-test"))
            implementation(project(":app-rpc"))
            implementation(project(":utils-kotlinx-io-coroutines-system"))
            implementation(project(":rpc-client"))
        }
    }
}
