plugins {
    id("kodex.kmp-view")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":app-contract-agent"))
            api(project(":app-contract-history"))
        }
        commonTest.dependencies {
            implementation(project(":agent-storage-in-memory"))
        }
        mosaicMain.dependencies {
            implementation(project(":app-view-components"))
            implementation(project(":app-view-patch"))
            implementation(libs.kotlin.logging)
            implementation(libs.mosaic.runtime)
        }
        mosaicTest.dependencies {
            implementation(project(":agent-session-in-memory"))
            implementation(project(":agent-session-test"))
            implementation(project(":agent-storage-contract-ext"))
            implementation(project(":app-viewmodel-history"))
            implementation(project(":utils-coroutines"))
            implementation(libs.mosaic.testing)
        }
    }
}
