plugins {
    kotlin("multiplatform")
    `maven-publish`
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
    mingwX64()

    sourceSets {
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
