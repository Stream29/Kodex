import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.kotlin
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

internal fun Project.configureCoordinates() {
    group = "io.github.stream29"
    version = "0.1.0-SNAPSHOT"
}

internal fun KotlinMultiplatformExtension.configureCompiler() {
    explicitApi()
    jvmToolchain(21)

    compilerOptions {
        allWarningsAsErrors.set(true)
    }
}

internal fun KotlinMultiplatformExtension.configureHostTargets() {
    jvm()

    linuxX64()
    linuxArm64()
    macosArm64()
    mingwX64 {
        binaries.all {
            linkerOpts("-lole32")
        }
    }
}

internal fun KotlinMultiplatformExtension.configureCommonTests(project: Project) {
    val libs = project.extensions.getByType(VersionCatalogsExtension::class.java).named("libs")
    sourceSets.getByName("commonTest").dependencies {
        implementation(project.dependencies.kotlin("test"))
        implementation(libs.findLibrary("test-balloon-framework-core").get())
    }
}
