import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.konan.target.Family

plugins {
    id("kodex.kmp-spec-impl")
    id("kodex.kmp-cli")
    id("kodex.kmp-tests")
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    applyHierarchyTemplate(org.jetbrains.kotlin.gradle.plugin.KotlinHierarchyTemplate.default) {
        common {
            group("posix") {
                withLinuxX64()
                withLinuxArm64()
                withMacosArm64()
            }
        }
    }
    targets.withType<KotlinNativeTarget>().configureEach {
        compilations.getByName("main").cinterops.create("backendPlatform") {
            definitionFile.set(project.file("src/nativeInterop/cinterop/backend_platform.def"))
        }
        if (konanTarget.family == Family.MINGW) {
            binaries.all { linkerOpts("-lbcrypt") }
        }
    }
    sourceSets {
        named("specMain") {
            dependencies {
                api(libs.kotlinx.coroutines.core)
                api(libs.kotlinx.io.core)
                api(libs.kotlinx.serialization.core)
            }
        }
        commonMain.dependencies {
            implementation(project(":utils-kotlinx-io-coroutines-system"))
            implementation(project(":utils-os-environment"))
            implementation(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
