plugins {
    id("kodex.kmp-host")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(libs.ktor.client.core)
            api(libs.ktor.sse)
        }
        jvmMain.dependencies {
            api(libs.bundles.ktor.client.jvm.engines)
        }
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
