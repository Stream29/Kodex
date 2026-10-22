plugins {
    id("kodex.kmp-spec-impl")
    id("kodex.kmp-cli")
}

kotlin {
    applyDefaultHierarchyTemplate()
    sourceSets {
        named("specMain").dependencies {
            api(project(":app-backend-daemon"))
            api(libs.kotlinx.serialization.json)
        }
        commonMain.dependencies {
            implementation(libs.ktor.client.core)
        }
        macosArm64Main.dependencies { implementation(libs.ktor.client.darwin) }
        named("linuxMain").dependencies { implementation(libs.ktor.client.curl) }
        mingwX64Main.dependencies { implementation(libs.ktor.client.winhttp) }
    }
}
