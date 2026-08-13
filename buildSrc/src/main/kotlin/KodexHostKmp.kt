import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.kotlin
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.KotlinHierarchyTemplate

internal fun Project.configureCoordinates() {
    group = "io.github.stream29"
    version = "0.2.2"
}

internal fun KotlinMultiplatformExtension.configureCompiler() {
    explicitApi()
    jvmToolchain(26)

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

@OptIn(ExperimentalKotlinGradlePluginApi::class)
internal fun KotlinMultiplatformExtension.configureMosaicHierarchy() {
    applyHierarchyTemplate(KotlinHierarchyTemplate.default) {
        common {
            group("mosaic") {
                withJvm()
                withLinuxX64()
                withLinuxArm64()
                withMacosArm64()
                withMingwX64()
            }
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
