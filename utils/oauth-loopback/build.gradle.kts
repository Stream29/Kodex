plugins {
    id("kodex.spec-api-module")
    id("kodex.kmp-spec-impl")
    id("kodex.kmp-host")
    id("kodex.ktor-native-network-override")
    id("kodex.kmp-tests")
}

kotlin {
    sourceSets {
        named("specMain").dependencies {
            api(libs.kotlinx.coroutines.core)
        }
        nativeMain.dependencies {
            implementation(libs.ktor.server.core)
            implementation(libs.ktor.server.cio)
        }
        nativeTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
