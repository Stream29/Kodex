plugins {
    id("kodex.kmp-spec-impl")
    id("kodex.kmp-cli")
}

kotlin {
    applyDefaultHierarchyTemplate()
    sourceSets {
        commonMain.dependencies {
            api(project(":app-backend-daemon"))
            api(libs.kotlinx.serialization.json)
            implementation(libs.ktor.client.core)
        }
        jvmMain.dependencies { implementation(libs.ktor.client.cio) }
        macosArm64Main.dependencies { implementation(libs.ktor.client.darwin) }
        named("linuxMain").dependencies { implementation(libs.ktor.client.curl) }
        mingwX64Main.dependencies { implementation(libs.ktor.client.winhttp) }
    }
}
