@file:OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)

import org.jetbrains.kotlin.gradle.plugin.KotlinHierarchyTemplate

plugins {
    id("codexlite.kmp-host")
}

kotlin {
    applyHierarchyTemplate(KotlinHierarchyTemplate.default) {
        common {
            group("lucene") {
                withJvm()
                withNative()
            }
        }
    }

    sourceSets {
        val luceneMain by getting {
            dependencies {
                implementation(libs.lucene.kmp.core)
            }
        }
    }
}
