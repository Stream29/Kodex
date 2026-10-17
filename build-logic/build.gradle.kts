plugins {
    `kotlin-dsl`
}

kotlin {
    jvmToolchain(25)
}

repositories {
    google()
    mavenCentral()
    gradlePluginPortal()
}

dependencies {
    implementation(libs.kotlin.gradle.plugin)
    implementation(libs.kotlin.compose.compiler.gradle.plugin)
    implementation(libs.test.balloon.gradle.plugin)
}
