plugins {
    id("codexlite.kmp-host")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(libs.ktor.client.core)
        }
        jvmMain.dependencies {
            api(libs.bundles.ktor.client.jvm.engines)
        }
        jsMain.dependencies {
            api(libs.bundles.ktor.client.js.engines)
        }
        linuxX64Main.dependencies {
            api(libs.bundles.ktor.client.linux.engines)
        }
        linuxArm64Main.dependencies {
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
