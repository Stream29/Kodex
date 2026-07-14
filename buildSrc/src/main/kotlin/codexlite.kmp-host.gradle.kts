import org.gradle.api.artifacts.VersionCatalogsExtension

plugins {
    kotlin("multiplatform")
    `maven-publish`
    id("de.infix.testBalloon")
}

group = "io.github.stream29"
version = "0.1.0-SNAPSHOT"

kotlin {
    explicitApi()
    jvmToolchain(21)

    compilerOptions {
        allWarningsAsErrors.set(true)
    }

    jvm()

    js {
        nodejs {
            testTask {
                useMocha {
                    timeout = "120s"
                }
            }
        }
        binaries.library()
    }

    linuxX64()
    linuxArm64()
    macosArm64()
    mingwX64 {
        binaries.all {
            linkerOpts("-lole32")
        }
    }

    sourceSets {
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(
                project.extensions
                    .getByType<VersionCatalogsExtension>()
                    .named("libs")
                    .findLibrary("test-balloon-framework-core")
                    .get(),
            )
        }
    }
}
