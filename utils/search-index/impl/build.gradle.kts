@file:OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)

import org.jetbrains.kotlin.gradle.plugin.KotlinHierarchyTemplate

plugins {
    id("kodex.kmp-host")
    id("kodex.kmp-tests")
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
        commonMain.dependencies {
            api(project(":utils-search-index-spec"))
        }
        val luceneMain by getting {
            dependencies {
                implementation(libs.lucene.kmp.core)
            }
        }
    }
}
