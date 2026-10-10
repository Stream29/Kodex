plugins {
    id("kodex.kmp-cli")
    id("kodex.kmp-tests")
}

group = "org.jetbrains.kotlinx"

kotlin {
    targets.withType<org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget>().configureEach {
        // Binary kRPC 0.10.3 KLIBs refer to this exact utils unique name.
        compilerOptions.moduleName.set("org.jetbrains.kotlinx:utils")
    }

    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.coroutines.core)
            implementation("org.jetbrains.kotlinx:atomicfu:0.29.0")
        }
    }
}
