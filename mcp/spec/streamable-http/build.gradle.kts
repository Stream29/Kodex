plugins {
    id("kodex.kmp-host")
    id("kodex.kmp-tests")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":mcp-spec-contract"))
            api(libs.ktor.client.core)
            api(libs.mcp.kotlin.sdk.client)
            api(libs.kotlinx.coroutines.core)
            implementation(libs.ktor.sse)
        }
        jvmMain.dependencies {
            implementation(libs.bundles.ktor.client.jvm.engines)
        }
        linuxMain.dependencies {
            implementation(libs.bundles.ktor.client.linux.engines)
        }
        macosArm64Main.dependencies {
            implementation(libs.bundles.ktor.client.macos.engines)
        }
        mingwX64Main.dependencies {
            implementation(libs.bundles.ktor.client.mingw.engines)
        }
        commonTest.dependencies {
            implementation(libs.ktor.client.mock)
        }
    }
}
