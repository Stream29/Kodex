plugins {
    id("kodex.kmp-host")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(libs.ktor.client.core)
            api(libs.ktor.sse)
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
        }
        jvmTest.dependencies {
            implementation(libs.ktor.server.cio)
            implementation(libs.ktor.server.core)
        }
    }
}
