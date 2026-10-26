import org.gradle.api.artifacts.component.ModuleComponentSelector
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType

configurations.configureEach {
    val configuration = this
    resolutionStrategy.dependencySubstitution {
        all {
            val module = requested as? ModuleComponentSelector
            if (module?.group == "org.jetbrains.kotlinx" &&
                module.module == "kotlinx-rpc-utils" &&
                configuration.attributes.getAttribute(KotlinPlatformType.attribute) == KotlinPlatformType.native
            ) {
                useTarget(
                    project(":rpc-krpc-utils-patch"),
                    "kRPC 0.10.3 Native map accessors return live collections",
                )
            }
        }
    }
}
