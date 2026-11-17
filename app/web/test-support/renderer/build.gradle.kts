plugins {
    id("kodex.spec-api-module")
    id("kodex.kmp-spec-impl")
    id("kodex.kmp-web-executable")
    id("kodex.kmp-tests")
    alias(libs.plugins.compose.multiplatform)
}

kotlin {
    wasmJs {
        browser {
            commonWebpackConfig { outputFileName = "kodex-renderer-smoke.js" }
        }
    }
    sourceSets {
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(project(":app-component-widgets"))
            implementation(project(":app-component-session-catalog-view"))
            implementation(project(":app-component-settings-view"))
            implementation(project(":app-component-composer-view"))
            implementation(project(":app-component-history-view"))
            implementation(project(":app-component-application-viewmodel"))
            implementation(project(":app-component-application-view"))
            implementation(project(":app-rpc"))
            implementation(project(":rpc-client"))
            implementation(project(":rpc-websocket"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
        }
        wasmJsMain.dependencies {
            implementation(project(":app-web-renderer"))
            implementation(compose.material3)
            implementation(compose.foundation)
            implementation(compose.ui)
            implementation(libs.ktor.client.js)
            implementation(libs.ktor.client.websockets)
            implementation(libs.kotlinx.rpc.krpc.client)
            implementation(libs.kotlinx.rpc.krpc.serialization.json)
        }
        commonTest.dependencies { implementation(libs.kotlinx.coroutines.test) }
    }
}
