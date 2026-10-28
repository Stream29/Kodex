import org.gradle.api.Plugin
import org.gradle.api.Project

class SpecApiModulePlugin : Plugin<Project> {
    override fun apply(target: Project): Unit = with(target) {
        val reportFile = layout.buildDirectory.file("reports/spec-api/module.sarif")
        val emptyReport = tasks.register("emptySpecApiModuleReport", NormalizeSpecApiReport::class.java) {
            output.set(reportFile)
        }
        val check = tasks.register("checkSpecApiModule")
        val report = tasks.register("reportSpecApiModule") { dependsOn(emptyReport) }
        pluginManager.withPlugin("org.jetbrains.kotlin.multiplatform") {
            emptyReport.configure { enabled = false }
            pluginManager.apply(SpecApiPlugin::class.java)
            check.configure { dependsOn("checkSpecApiModuleAnalysis") }
            report.configure { dependsOn("normalizeSpecApiModuleReport") }
        }
    }
}
