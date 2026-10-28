plugins {
    id("kodex.spec-api-module")
    id("kodex.kmp-spec-impl")
    id("kodex.kmp-host")
    id("kodex.kmp-tests")
}

kotlin {
    sourceSets {
        named("specMain").dependencies {
            api(project(":utils-patch"))
        }
        commonMain.dependencies {
            implementation(project(":utils-kotlinx-io-coroutines-system"))
        }
    }
}
