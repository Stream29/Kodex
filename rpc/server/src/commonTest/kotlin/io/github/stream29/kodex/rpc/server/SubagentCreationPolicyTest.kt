package io.github.stream29.kodex.rpc.server

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.KodexAgentSettings
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.SubagentCreationMode
import io.github.stream29.kodex.openai.ModelInfo
import io.github.stream29.kodex.openai.ModelServiceTier
import io.github.stream29.kodex.openai.OpenAiModelId
import io.github.stream29.kodex.openai.Reasoning
import io.github.stream29.kodex.openai.ReasoningEffort
import io.github.stream29.kodex.openai.ReasoningEffortPreset
import io.github.stream29.kodex.openai.RequestUserInputMode
import io.github.stream29.kodex.openai.ServiceTier
import io.github.stream29.kodex.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import kotlinx.io.files.Path
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

val subagentCreationPolicyTest by testSuite {
    test("a rejecting parent avoids even consulting the model catalog") {
        val parent = KodexAgentSettings(OpenAiModelId("parent"), subagentCreationMode = SubagentCreationMode.Reject)
        assertEquals(SubagentCreationDecision.Rejected.CreationRejected, decideSubagentCreation(
            parent, null, null, "not an absolute directory",
        ) { error("No model catalog lookup for rejected policy") })
    }

    test("one snapshot provides defaults and explicit overrides do not copy history or run identity") {
        val parentDirectory = SystemCoroutineFileSystem.resolve(Path("."))
        val childDirectory = SystemCoroutineFileSystem.resolve(SystemTemporaryDirectory)
        val parent = KodexAgentSettings(
            model = OpenAiModelId("default-model"),
            reasoning = Reasoning(ReasoningEffort.High),
            serviceTier = ServiceTier.Ultrafast,
            cwd = parentDirectory,
            requestUserInputMode = RequestUserInputMode.NoQuestion,
            threadName = "do not copy",
            turnId = "parent-turn",
            turnState = "private provider state",
            windowId = "parent-window",
            sessionId = "parent-provider-session",
        )
        val effective = assertIs<SubagentCreationDecision.Allowed>(
            decideSubagentCreation(parent, null, null, null) { emptyList() },
        ).settings
        assertEquals(parent.model, effective.model)
        assertEquals(parent.reasoning.effort, effective.reasoning.effort)
        assertEquals(parentDirectory, effective.cwd)
        assertEquals(parent.serviceTier, effective.serviceTier)
        assertEquals(parent.requestUserInputMode, effective.requestUserInputMode)
        assertEquals(SubagentCreationMode.AutoApprove, effective.subagentCreationMode)
        assertEquals("", effective.threadName)
        assertEquals("", effective.turnId)
        assertEquals("", effective.windowId)
        assertNull(effective.turnState)
        assertNull(effective.sessionId)

        val overriddenModel = OpenAiModelId("selected-model")
        val catalog = listOf(ModelInfo(
            slug = overriddenModel,
            displayName = "Selected",
            supportedReasoningLevels = listOf(ReasoningEffortPreset(ReasoningEffort.Medium, "Medium")),
            serviceTiers = listOf(ModelServiceTier(checkNotNull(parent.serviceTier.requestValue), "Fast", "Fast routing")),
        ))
        val overridden = assertIs<SubagentCreationDecision.Allowed>(
            decideSubagentCreation(parent, overriddenModel, ReasoningEffort.Medium, childDirectory.toString()) { catalog },
        ).settings
        assertEquals(effective.copy(model = overriddenModel, reasoning = Reasoning(ReasoningEffort.Medium), cwd = childDirectory), overridden)
        assertEquals(ReasoningEffort.High, parent.reasoning.effort)
    }

    test("invalid directory, model, effort and service tier are rejected before creation") {
        val cwd = SystemCoroutineFileSystem.resolve(SystemTemporaryDirectory)
        val known = OpenAiModelId("known")
        val parent = KodexAgentSettings(known, cwd = cwd)
        val catalog = listOf(ModelInfo(
            known, "Known",
            supportedReasoningLevels = listOf(ReasoningEffortPreset(ReasoningEffort.Medium, "Medium")),
        ))
        assertEquals(SubagentCreationDecision.Rejected.InvalidDirectory,
            decideSubagentCreation(parent, null, null, "relative") { catalog })
        assertEquals(SubagentCreationDecision.Rejected.InvalidDirectory,
            decideSubagentCreation(parent, null, null, "\u0000") { catalog })
        assertEquals(SubagentCreationDecision.Rejected.InvalidConfiguration,
            decideSubagentCreation(parent, OpenAiModelId("missing"), null, null) { catalog })
        assertEquals(SubagentCreationDecision.Rejected.InvalidConfiguration,
            decideSubagentCreation(parent, known, ReasoningEffort.High, null) { catalog })
        assertEquals(SubagentCreationDecision.Rejected.InvalidConfiguration,
            decideSubagentCreation(parent.copy(serviceTier = ServiceTier.Ultrafast), known, null, null) { catalog })
    }
}
