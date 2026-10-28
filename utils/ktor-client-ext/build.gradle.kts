plugins {
    id("kodex.spec-api-module")
    id("kodex.kmp-spec-impl")
    id("kodex.kmp-host")
    id("kodex.kmp-tests")
}

kotlin {
    sourceSets {
        linuxMain.dependencies {
            api(libs.bundles.ktor.client.linux.engines)
        }
        macosArm64Main.dependencies {
            api(libs.bundles.ktor.client.macos.engines)
        }
        mingwX64Main.dependencies {
            api(libs.bundles.ktor.client.mingw.engines)
        }
    }
}

kotlin {
    sourceSets {
        named("specMain") {
            dependencies {
                api(libs.ktor.client.core)
                api(libs.ktor.sse)
                api(libs.kotlinx.coroutines.core)
            }
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
