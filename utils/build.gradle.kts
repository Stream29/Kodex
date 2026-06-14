import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("codexlite.kmp-library")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.coroutines.core)
        }
    }
}
