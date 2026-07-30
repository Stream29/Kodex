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
            api(project(":cli-session"))
            api(project(":cli-settings-contract"))
            implementation(project(":openai-models"))
            implementation(project(":utils-coroutines"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.io.core)
        }
        commonTest.dependencies {
            implementation(project(":agent-session-in-memory"))
            implementation(project(":agent-session-test"))
            implementation(libs.kotlinx.coroutines.test)
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
