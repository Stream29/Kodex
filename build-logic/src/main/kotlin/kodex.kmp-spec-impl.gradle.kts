@file:OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)

plugins {
    kotlin("multiplatform")
}

kotlin {
    // A manual dependsOn edge prevents implicit template application. Make the
    // default hierarchy explicit; projects can extend it with their own groups.
    applyDefaultHierarchyTemplate()

    sourceSets {
        val specMain = create("specMain")
        commonMain {
            dependsOn(specMain)
        }
    }
}
