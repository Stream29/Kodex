plugins {
    `kotlin-dsl`
}

kotlin {
    jvmToolchain(8)
}

repositories {
    google()
    mavenCentral()
    gradlePluginPortal()
}

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.0")
}
