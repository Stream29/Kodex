plugins {
    id("kodex.spec-api-module")
    id("kodex.kmp-spec-impl")
    id("kodex.kmp-host")
    id("kodex.kmp-tests")
}

kotlin {
    sourceSets {
        named("specMain").dependencies {
            api(project(":openai-account-usage"))
            api(project(":openai-client"))
        }
        commonMain.dependencies {
            implementation(project(":openai-models"))
        }
    }
}
