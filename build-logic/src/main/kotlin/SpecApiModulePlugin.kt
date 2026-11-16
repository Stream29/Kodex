import org.gradle.api.Plugin
import org.gradle.api.Project

class SpecApiModulePlugin : Plugin<Project> {
    override fun apply(target: Project): Unit = with(target) {
        val check = tasks.register("checkSpecApiModule")
        val report = tasks.register("reportSpecApiModule")
        pluginManager.withPlugin("org.jetbrains.kotlin.multiplatform") {
            pluginManager.apply(SpecApiPlugin::class.java)
            check.configure { dependsOn("checkSpecApiModuleAnalysis") }
            report.configure { dependsOn("normalizeSpecApiModuleReport") }
        }
    }
}
