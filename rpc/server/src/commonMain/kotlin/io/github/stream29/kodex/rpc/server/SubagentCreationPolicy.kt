package io.github.stream29.kodex.rpc.server

import io.github.stream29.kodex.agentstorage.cleanmodels.stable.KodexAgentSettings
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.SubagentCreationMode
import io.github.stream29.kodex.openai.ModelInfo
import io.github.stream29.kodex.openai.OpenAiModelId
import io.github.stream29.kodex.openai.Reasoning
import io.github.stream29.kodex.openai.ReasoningEffort
import io.github.stream29.kodex.openai.ServiceTier
import io.github.stream29.kodex.openai.availableServiceTiers
import io.github.stream29.kodex.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import kotlinx.coroutines.CancellationException
import kotlinx.io.files.Path

internal sealed interface SubagentCreationDecision {
    data class Allowed(val settings: KodexAgentSettings) : SubagentCreationDecision

    enum class Rejected : SubagentCreationDecision {
        CreationRejected,
        InvalidDirectory,
        InvalidConfiguration,
    }
}

/** Decides from one captured parent settings snapshot. Does not open or create an Agent. */
internal suspend fun decideSubagentCreation(
    parent: KodexAgentSettings,
    modelOverride: OpenAiModelId?,
    effortOverride: ReasoningEffort?,
    directoryOverride: String?,
    models: () -> List<ModelInfo>,
): SubagentCreationDecision {
    if (parent.subagentCreationMode == SubagentCreationMode.Reject) {
        return SubagentCreationDecision.Rejected.CreationRejected
    }
    val cwd = resolveSpawnDirectory(
        directoryOverride ?: parent.cwd.toString(),
        mustBeAbsolute = directoryOverride != null,
    ) ?: return SubagentCreationDecision.Rejected.InvalidDirectory
    val model = modelOverride ?: parent.model
    val effort = effortOverride ?: parent.reasoning.effort
    if (!supportsSpawnConfiguration(models(), model, modelOverride != null, effort, parent.serviceTier)) {
        return SubagentCreationDecision.Rejected.InvalidConfiguration
    }
    return SubagentCreationDecision.Allowed(KodexAgentSettings(
        model = model,
        reasoning = Reasoning(effort = effort),
        serviceTier = parent.serviceTier,
        requestUserInputMode = parent.requestUserInputMode,
        cwd = cwd,
    ))
}

internal fun supportsSpawnConfiguration(
    catalog: List<ModelInfo>,
    model: OpenAiModelId,
    explicitModel: Boolean,
    effort: ReasoningEffort,
    tier: ServiceTier,
): Boolean {
    val info = catalog.firstOrNull { it.slug == model }
    if (info == null) return !explicitModel || catalog.isEmpty()
    return (info.supportedReasoningLevels.isEmpty() || info.supportedReasoningLevels.any { it.effort == effort }) &&
        tier in info.availableServiceTiers()
}

internal suspend fun resolveSpawnDirectory(value: String, mustBeAbsolute: Boolean = true): Path? {
    if (value.isBlank() || '\u0000' in value) return null
    return try {
        val path = Path(value)
        if (mustBeAbsolute && !path.isAbsolute) return null
        val resolved = SystemCoroutineFileSystem.resolve(path)
        resolved.takeIf { SystemCoroutineFileSystem.metadataOrNull(it)?.isDirectory == true }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }
}
