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
    implementation("dev.detekt:detekt-gradle-plugin:${libs.versions.detekt.get()}")
}

gradlePlugin {
    plugins {
        register("specApi") {
            id = "kodex.spec-api"
            implementationClass = "SpecApiPlugin"
        }
        register("specApiModule") {
            id = "kodex.spec-api-module"
            implementationClass = "SpecApiModulePlugin"
        }
    }
}
