plugins {
    id("kodex.kmp-spec-impl")
    id("kodex.kmp-web")
    id("kodex.kmp-tests")
}

kotlin {
    sourceSets {
        named("specMain").dependencies {
            api(project(":app-rpc-protocol"))
            api(project(":rpc-connection"))
        }
        commonMain.dependencies {
            implementation(project(":rpc-websocket"))
            implementation(project(":rpc-client"))
            implementation(libs.kotlinx.rpc.krpc.client)
            implementation(libs.kotlinx.rpc.krpc.serialization.json)
            implementation(libs.ktor.client.js)
            implementation(libs.ktor.client.websockets)
            implementation(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
            implementation(project(":app-rpc"))
        }
    }
}
