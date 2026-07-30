plugins {
    id("codexlite.kmp-cli")
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
            api(project(":agent-runtime-contract"))
            api(project(":agent-session-contract"))
            api(project(":tool-request-user-input-contract"))
            implementation(project(":cli-session-title"))
            implementation(project(":agent-storage-clean-models"))
            implementation(project(":utils-coroutines"))
            implementation(libs.kotlinx.coroutines.core)
        }

        val mosaicMain by creating {
            dependsOn(commonMain)
            dependencies {
                implementation(project(":cli-components"))
                implementation(libs.mosaic.runtime)
            }
        }

        jvmMain.dependsOn(mosaicMain)
        linuxX64Main.dependsOn(mosaicMain)
        linuxArm64Main.dependsOn(mosaicMain)
        macosArm64Main.dependsOn(mosaicMain)
        mingwX64Main.dependsOn(mosaicMain)

        listOf(commonTest, jvmTest, linuxX64Test, linuxArm64Test, macosArm64Test, mingwX64Test)
            .forEach { sourceSet -> sourceSet.dependencies { implementation(kotlin("test")) } }
    }
}
