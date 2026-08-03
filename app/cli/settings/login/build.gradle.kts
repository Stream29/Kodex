plugins {
    id("kodex.kmp-cli")
    alias(libs.plugins.kotlin.compose)
}

kotlin {
    applyDefaultHierarchyTemplate()

    sourceSets {
        val commonMain by getting
        val commonTest by getting
        val jvmMain by getting
        val jvmTest by getting
        val linuxX64Main by getting
        val linuxX64Test by getting
        val linuxArm64Main by getting
        val linuxArm64Test by getting
        val macosArm64Main by getting
        val macosArm64Test by getting
        val mingwX64Main by getting
        val mingwX64Test by getting

        commonMain.dependencies {
            api(project(":app-shared-settings-login"))
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
        }

        val mosaicMain by creating {
            dependsOn(commonMain)
            dependencies {
                implementation(project(":app-cli-components"))
                implementation(project(":utils-external-url"))
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.mosaic.runtime)
            }
        }
        val mosaicTest by creating {
            dependsOn(commonTest)
            dependencies {
                implementation(libs.mosaic.testing)
            }
        }

        listOf(jvmMain, linuxX64Main, linuxArm64Main, macosArm64Main, mingwX64Main).forEach { sourceSet ->
            sourceSet.dependsOn(mosaicMain)
        }
        listOf(jvmTest, linuxX64Test, linuxArm64Test, macosArm64Test, mingwX64Test).forEach { sourceSet ->
            sourceSet.dependsOn(mosaicTest)
        }
    }
}
