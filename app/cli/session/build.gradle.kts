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
        val linuxX64Main by getting
        val linuxArm64Main by getting
        val macosArm64Main by getting
        val mingwX64Main by getting

        commonMain.dependencies {
            api(project(":app-shared-session"))
            implementation(project(":app-cli-agent"))
        }

        val mosaicMain by creating {
            dependsOn(commonMain)
            dependencies {
                implementation(libs.mosaic.runtime)
            }
        }

        jvmMain.dependsOn(mosaicMain)
        linuxX64Main.dependsOn(mosaicMain)
        linuxArm64Main.dependsOn(mosaicMain)
        macosArm64Main.dependsOn(mosaicMain)
        mingwX64Main.dependsOn(mosaicMain)
    }
}
