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
    implementation("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.0")
    implementation(libs.kotlin.compose.compiler.gradle.plugin)
    implementation("io.insert-koin:koin-compiler-gradle-plugin:1.0.1")
    implementation(libs.test.balloon.gradle.plugin)
}
