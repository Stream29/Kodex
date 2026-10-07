plugins {
    id("kodex.kmp-host")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":utils-ktor-client-ext-spec"))
        }
        jvmMain.dependencies {
            api(libs.bundles.ktor.client.jvm.engines)
        }
        jvmTest.dependencies {
            implementation(libs.ktor.server.cio)
            implementation(libs.ktor.server.core)
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
