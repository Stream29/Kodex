package io.github.stream29.kodex.hook.impl

import io.github.stream29.kodex.hook.contract.HookCodexImportCandidate
import io.github.stream29.kodex.hook.contract.HookCodexImportIdentity
import io.github.stream29.kodex.hook.contract.HookCodexImportSource
import io.github.stream29.kodex.hook.contract.HookCodexImportTemplate
import io.github.stream29.kodex.hook.contract.HookCodexSourceKind
import io.github.stream29.kodex.hook.contract.HookCommandDefinition
import io.github.stream29.kodex.hook.contract.HookConfiguration
import io.github.stream29.kodex.hook.contract.HookConfigurationStore
import io.github.stream29.kodex.hook.contract.HookDeclarations
import io.github.stream29.kodex.hook.contract.HookEnvironmentDraft
import io.github.stream29.kodex.hook.contract.HookEnvironmentValue
import io.github.stream29.kodex.hook.contract.HookEvent
import io.github.stream29.kodex.hook.contract.HookImportDecision
import io.github.stream29.kodex.hook.contract.HookImportDisposition
import io.github.stream29.kodex.hook.contract.HookImportSupport
import io.github.stream29.kodex.hook.contract.HookMatcher
import io.github.stream29.kodex.hook.contract.HookMatcherGroup
import io.github.stream29.kodex.hook.contract.HookSourceConfiguration
import io.github.stream29.kodex.hook.contract.HookSourceDraft
import io.github.stream29.kodex.hook.contract.HookSourceIdFactory
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class HookManagerImplTest {
    @Test
    fun managementCommandsPersistCompleteSourcesWithoutPublishingEnvironmentValues() = runTest {
        val store = TestHookConfigurationStore()
        val ids = ArrayDeque(listOf("source-1"))
        val manager = backgroundScope.HookManagerImpl(
            store = store,
            codexImportSource = HookCodexImportSource { emptyList() },
            sourceIdFactory = HookSourceIdFactory { ids.removeFirst() },
        )

        manager.setFeatureEnabled(false)
        val sourceId = manager.add(
            HookSourceDraft(
                name = "  Local checks  ",
                environment = mapOf(
                    " TOKEN " to HookEnvironmentDraft.Replace("private-token"),
                ),
                hooks = declarations("first-command"),
            ),
        )
        runCurrent()

        assertEquals("source-1", sourceId)
        assertFalse(manager.featureEnabled.value)
        assertEquals(
            listOf("TOKEN"),
            manager.sources.value.single().environmentNames,
        )
        assertEquals(listOf(HookEvent.Stop), manager.sources.value.single().configuredEvents)
        assertFalse("private-token" in manager.sources.value.toString())
        assertEquals(
            HookEnvironmentDraft.Keep,
            manager.editorDraft(sourceId)?.environment?.get("TOKEN"),
        )
        assertFalse("private-token" in manager.editorDraft(sourceId).toString())

        manager.edit(
            sourceId = sourceId,
            draft = HookSourceDraft(
                name = "Updated checks",
                environment = mapOf("TOKEN" to HookEnvironmentDraft.Keep),
                hooks = declarations("updated-command", HookEvent.PreToolUse),
            ),
        )
        manager.setEnabled(sourceId, false)
        runCurrent()

        val persisted = store.configuration.value.sources.single()
        assertEquals("source-1", persisted.id)
        assertEquals("Updated checks", persisted.name)
        assertFalse(persisted.enabled)
        assertEquals(HookEnvironmentValue("private-token"), persisted.environment.getValue("TOKEN"))
        assertEquals(
            "updated-command",
            persisted.hooks.preToolUse.single().hooks.single().command,
        )

        manager.delete(sourceId)
        runCurrent()
        assertEquals(emptyList(), store.configuration.value.sources)
        assertNull(manager.editorDraft(sourceId))
        assertEquals(5, store.successfulUpdateCount)
        manager.close()
    }

    @Test
    fun invalidDraftDoesNotWriteAnySettings() = runTest {
        val store = TestHookConfigurationStore()
        val manager = backgroundScope.HookManagerImpl(
            store = store,
            codexImportSource = HookCodexImportSource { emptyList() },
            sourceIdFactory = HookSourceIdFactory { "unused" },
        )

        assertFailsWith<IllegalArgumentException> {
            manager.add(
                HookSourceDraft(
                    name = " ",
                    environment = mapOf(
                        "TOKEN" to HookEnvironmentDraft.Replace("private-token"),
                    ),
                    hooks = declarations("command"),
                ),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            manager.add(
                HookSourceDraft(
                    name = "Invalid matcher",
                    hooks = HookDeclarations(
                        stop = listOf(
                            HookMatcherGroup(
                                matcher = HookMatcher.Invalid("["),
                                hooks = listOf(HookCommandDefinition("command")),
                            ),
                        ),
                    ),
                ),
            )
        }

        assertEquals(0, store.successfulUpdateCount)
        assertEquals(HookConfiguration(), store.configuration.value)
        manager.close()
    }

    @Test
    fun importPreviewFiltersSanitizesAndAtomicallyAppliesWholeSources() = runTest {
        val userIdentity = identity(HookCodexSourceKind.User, "/codex/hooks.json")
        val projectIdentity = identity(HookCodexSourceKind.Project, "/work/.codex/config.toml")
        val unsupportedIdentity = identity(
            HookCodexSourceKind.Project,
            "/other/.codex/hooks.json",
        )
        val existing = HookSourceConfiguration(
            id = "stable-existing-id",
            name = "Existing imported source",
            importIdentity = userIdentity,
            environment = mapOf("OLD_TOKEN" to HookEnvironmentValue("old-secret")),
            hooks = declarations("old-command"),
        )
        val local = HookSourceConfiguration(
            id = "local-id",
            name = "Local source",
            hooks = declarations("local-command"),
        )
        val store = TestHookConfigurationStore(
            HookConfiguration(sources = listOf(existing, local)),
        )
        val manager = backgroundScope.HookManagerImpl(
            store = store,
            codexImportSource = HookCodexImportSource {
                listOf(
                    supportedCandidate(
                        identity = projectIdentity,
                        name = "Project checks",
                        command = "project-secret-command",
                        environment = mapOf("PROJECT_TOKEN" to "project-secret"),
                    ),
                    HookCodexImportCandidate.Unsupported(
                        identity = unsupportedIdentity,
                        displayName = "Unsupported source",
                        detail = "Prompt handlers are not supported.",
                    ),
                    supportedCandidate(
                        identity = userIdentity,
                        name = "User checks",
                        command = "replacement-secret-command",
                        environment = mapOf("USER_TOKEN" to "replacement-secret"),
                        excludedDetails = listOf("Asynchronous handlers were excluded."),
                    ),
                )
            },
            sourceIdFactory = HookSourceIdFactory { "new-project-id" },
        )

        val writesBeforePreview = store.successfulUpdateCount
        val filtered = manager.previewCodexImport("PROJECT checks")
        assertEquals(listOf(projectIdentity.key), filtered.items.map { it.sourceKey })
        assertEquals(writesBeforePreview, store.successfulUpdateCount)

        val preview = manager.previewCodexImport()
        assertEquals(writesBeforePreview, store.successfulUpdateCount)
        assertEquals(
            mapOf(
                unsupportedIdentity.key to Pair(null, HookImportSupport.Unsupported),
                projectIdentity.key to Pair(HookImportDisposition.New, HookImportSupport.Full),
                userIdentity.key to Pair(HookImportDisposition.Conflict, HookImportSupport.Partial),
            ),
            preview.items.associate { item ->
                item.sourceKey to Pair(item.disposition, item.support)
            },
        )
        assertFalse(
            preview.items.single { it.sourceKey == unsupportedIdentity.key }.selectable,
        )
        assertFalse("project-secret-command" in preview.toString())
        assertFalse("replacement-secret-command" in preview.toString())
        assertFalse("project-secret" in preview.toString())
        assertFalse("replacement-secret" in preview.toString())

        manager.applyCodexImport(
            previewId = preview.id,
            decisions = mapOf(
                projectIdentity.key to HookImportDecision.Import,
                userIdentity.key to HookImportDecision.Replace,
            ),
        )
        runCurrent()

        assertEquals(writesBeforePreview + 1, store.successfulUpdateCount)
        val imported = store.configuration.value.sources
        assertEquals(
            listOf("stable-existing-id", "local-id", "new-project-id"),
            imported.map(HookSourceConfiguration::id),
        )
        val replaced = imported.first()
        assertEquals(userIdentity, replaced.importIdentity)
        assertEquals("User checks", replaced.name)
        assertEquals(
            "replacement-secret-command",
            replaced.hooks.stop.single().hooks.single().command,
        )
        assertEquals(
            HookEnvironmentValue("replacement-secret"),
            replaced.environment.getValue("USER_TOKEN"),
        )
        assertEquals(projectIdentity, imported.last().importIdentity)
        assertFailsWith<IllegalArgumentException> {
            manager.applyCodexImport(preview.id, emptyMap())
        }
        manager.close()
    }

    @Test
    fun staleImportConflictRollsBackEveryDecision() = runTest {
        val firstIdentity = identity(HookCodexSourceKind.User, "/codex/hooks.json")
        val secondIdentity = identity(HookCodexSourceKind.Project, "/work/.codex/hooks.json")
        val store = TestHookConfigurationStore()
        val ids = ArrayDeque(listOf("first-id", "second-id"))
        val manager = backgroundScope.HookManagerImpl(
            store = store,
            codexImportSource = HookCodexImportSource {
                listOf(
                    supportedCandidate(firstIdentity, "First", "first"),
                    supportedCandidate(secondIdentity, "Second", "second"),
                )
            },
            sourceIdFactory = HookSourceIdFactory { ids.removeFirst() },
        )
        val preview = manager.previewCodexImport()
        store.update { current ->
            current.copy(
                sources = listOf(
                    HookSourceConfiguration(
                        id = "external-id",
                        name = "External import",
                        importIdentity = secondIdentity,
                        hooks = declarations("external"),
                    ),
                ),
            )
        }
        runCurrent()
        assertEquals(listOf("external-id"), manager.sources.value.map { it.sourceId })
        val beforeApply = store.configuration.value
        val writesBeforeApply = store.successfulUpdateCount

        assertFailsWith<IllegalArgumentException> {
            manager.applyCodexImport(
                previewId = preview.id,
                decisions = mapOf(
                    firstIdentity.key to HookImportDecision.Import,
                    secondIdentity.key to HookImportDecision.Import,
                ),
            )
        }

        assertEquals(beforeApply, store.configuration.value)
        assertEquals(writesBeforeApply, store.successfulUpdateCount)
        manager.close()
    }
}

private class TestHookConfigurationStore(
    initial: HookConfiguration = HookConfiguration(),
) : HookConfigurationStore {
    private val mutex = Mutex()
    override val configuration = MutableStateFlow(initial)
    var successfulUpdateCount: Int = 0

    override suspend fun update(
        transform: (HookConfiguration) -> HookConfiguration,
    ): HookConfiguration =
        mutex.withLock {
            val updated = transform(configuration.value)
            configuration.value = updated
            successfulUpdateCount += 1
            updated
        }
}

private fun declarations(
    command: String,
    event: HookEvent = HookEvent.Stop,
): HookDeclarations =
    HookDeclarations().withGroups(
        event = event,
        groups = listOf(
            HookMatcherGroup(
                hooks = listOf(HookCommandDefinition(command)),
            ),
        ),
    )

private fun identity(
    sourceKind: HookCodexSourceKind,
    path: String,
): HookCodexImportIdentity =
    HookCodexImportIdentity(
        sourceKind = sourceKind,
        normalizedPath = path,
    )

private fun supportedCandidate(
    identity: HookCodexImportIdentity,
    name: String,
    command: String,
    environment: Map<String, String> = emptyMap(),
    excludedDetails: List<String> = emptyList(),
): HookCodexImportCandidate.Supported =
    HookCodexImportCandidate.Supported(
        identity = identity,
        displayName = name,
        template = HookCodexImportTemplate(
            name = name,
            environment = environment.mapValues { (_, value) -> HookEnvironmentValue(value) },
            hooks = declarations(command),
        ),
        excludedDetails = excludedDetails,
    )
