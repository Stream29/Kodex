plugins {
    id("codexlite.kmp-shared")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(
                libs.unicode.segmentation.kotlin.get().copy().apply {
                    isTransitive = false
                },
            )
            implementation(
                libs.unicode.width.kotlin.get().copy().apply {
                    isTransitive = false
                },
            )
        }
    }
}
