plugins {
    id("codexlite.kmp-host")
}

kotlin {
    sourceSets {
        fun org.jetbrains.kotlin.gradle.plugin.KotlinSourceSet.useLucene() {
            kotlin.srcDir("src/luceneMain/kotlin")
            dependencies {
                implementation(libs.lucene.kmp.core)
            }
        }

        jvmMain.get().useLucene()
        linuxX64Main.get().useLucene()
        linuxArm64Main.get().useLucene()
        macosArm64Main.get().useLucene()
        mingwX64Main.get().useLucene()

        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
