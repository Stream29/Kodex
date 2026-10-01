plugins {
    id("kodex.kmp-view")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":app-component-openai-login-spec"))
        }
        mosaicMain.dependencies {
            api(project(":utils-external-url-spec"))
            implementation(project(":app-view-components"))
            implementation(project(":utils-external-url-impl"))
            implementation(libs.mosaic.runtime)
        }
        mosaicTest.dependencies {
            implementation(project(":app-component-openai-login-impl-viewmodel"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.mosaic.testing)
        }
    }
}
