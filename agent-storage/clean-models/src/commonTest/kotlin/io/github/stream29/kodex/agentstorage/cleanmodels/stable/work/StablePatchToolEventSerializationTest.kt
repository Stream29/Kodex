package io.github.stream29.kodex.agentstorage.cleanmodels.stable.work

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.openai.FunctionCallOutputBody
import io.github.stream29.kodex.openai.FunctionCallOutputPayload
import io.github.stream29.kodex.openai.ResponseItem
import io.github.stream29.kodex.openai.ResponseItemId
import io.github.stream29.kodex.openai.jsoncodec.OpenAiJsonCodec
import io.github.stream29.kodex.utils.applypatch.Patch
import io.github.stream29.kodex.utils.applypatch.PatchAffectedPaths
import io.github.stream29.kodex.utils.applypatch.PatchApplyResult
import io.github.stream29.kodex.utils.applypatch.applyToFileSystem
import io.github.stream29.kodex.utils.applypatch.parsePatch
import io.github.stream29.kodex.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import kotlinx.io.files.Path
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private val json = Json

val stablePatchToolEventSerializationTest by testSuite {
    test("round trips successful patch tool event") {
        val patchText = """
            *** Begin Patch
            *** Update File: old.txt
            *** Move to: new.txt
            @@ heading
            -old
            +new
            *** End Patch
        """.trimIndent()
        val event = StablePatchToolEvent(
            callId = "call_apply_patch",
            itemId = ResponseItemId("item_apply_patch"),
            diff = patchText.parsePatch(),
            result = StablePatchToolExecutionResult.Success(
                applyResult = PatchApplyResult(
                    affectedPaths = PatchAffectedPaths(
                        added = emptyList(),
                        modified = listOf("new.txt"),
                        deleted = emptyList(),
                    ),
                ),
            ),
        )

        val encoded = json.encodeToString(event)
        val element = json.parseToJsonElement(encoded).jsonObject

        assertEquals(
            setOf("call_id", "item_id", "diff", "result"),
            element.keys,
        )
        val firstHunkType = element["diff"]
            ?.jsonObject
            ?.get("hunks")
            ?.jsonArray
            ?.first()
            ?.jsonObject
            ?.get("type")
        assertEquals(JsonPrimitive("update_file"), firstHunkType)
        assertEquals(JsonPrimitive("success"), element["result"]?.jsonObject?.get("type"))
        assertEquals(
            setOf("affected_paths"),
            element["result"]
                ?.jsonObject
                ?.get("apply_result")
                ?.jsonObject
                ?.keys,
        )
        assertEquals(event, json.decodeFromString<StablePatchToolEvent>(encoded))
        assertEquals(
            listOf(
                ResponseItem.CustomToolCall(
                    id = event.itemId,
                    callId = event.callId,
                    name = "apply_patch",
                    input = event.diff.patch,
                ),
                ResponseItem.CustomToolCallOutput(
                    callId = event.callId,
                    output = FunctionCallOutputPayload(
                        body = FunctionCallOutputBody.Text("Success. Patch applied."),
                        success = true,
                    ),
                ),
            ),
            event.toResponseHistoryItems(),
        )
    }

    test("ignores legacy full-content delta while decoding") {
        val event = successfulPatchEvent()
        val encoded = json.encodeToJsonElement(StablePatchToolEvent.serializer(), event).jsonObject
        val result = encoded.getValue("result").jsonObject
        val applyResult = result.getValue("apply_result").jsonObject
        val legacyDelta = json.parseToJsonElement(
            """
            {
              "changes": [
                {
                  "path": "large.txt",
                  "change": {
                    "type": "update",
                    "move_path": null,
                    "old_content": "legacy old content",
                    "overwritten_move_content": null,
                    "new_content": "legacy new content"
                  }
                }
              ],
              "exact": true
            }
            """.trimIndent(),
        )
        val legacy = JsonObject(
            encoded + (
                "result" to JsonObject(
                    result + (
                        "apply_result" to JsonObject(
                            applyResult + ("delta" to legacyDelta),
                        )
                    ),
                )
            ),
        )

        assertEquals(
            event,
            OpenAiJsonCodec.decodeFromJsonElement(StablePatchToolEvent.serializer(), legacy),
        )
    }

    test("serialized size does not retain target file snapshots") {
        val root = Path(SystemTemporaryDirectory, "kodex-patch-size-${Random.nextLong()}")
        SystemCoroutineFileSystem.createDirectories(root)
        try {
            val patch = updatePatch()
            val target = Path(root, "large.txt")
            SystemCoroutineFileSystem.writeString(target, "old\n")
            val smallResult = patch.applyToFileSystem(root, SystemCoroutineFileSystem)
            val small = json.encodeToString(successfulPatchEvent(patch, smallResult))

            val largeTargetSentinel = "target-only-content"
            SystemCoroutineFileSystem.writeString(
                target,
                buildString {
                    repeat(20_000) {
                        append(largeTargetSentinel)
                        append('\n')
                    }
                    append("old\n")
                },
            )
            val largeResult = patch.applyToFileSystem(root, SystemCoroutineFileSystem)
            val large = json.encodeToString(successfulPatchEvent(patch, largeResult))

            assertEquals(small.length, large.length)
            assertFalse(largeTargetSentinel in large)
            assertTrue(large.length < 2_000)
        } finally {
            deleteRecursively(root)
        }
    }

    test("overwrite and delete results do not retain replaced content") {
        val root = Path(SystemTemporaryDirectory, "kodex-patch-overwrite-size-${Random.nextLong()}")
        SystemCoroutineFileSystem.createDirectories(root)
        try {
            snapshotScenarios().forEach { scenario ->
                val scenarioRoot = Path(root, scenario.name)
                SystemCoroutineFileSystem.createDirectories(scenarioRoot)
                scenario.prepare(scenarioRoot, "small target content\n")
                val small = scenario.patch.applyAndSerialize(scenarioRoot)

                val sentinel = "target-only-${scenario.name}"
                scenario.prepare(scenarioRoot, "$sentinel\n".repeat(20_000))
                val large = scenario.patch.applyAndSerialize(scenarioRoot)

                assertEquals(
                    expected = small.length,
                    actual = large.length,
                    message = "${scenario.name} result size must not depend on replaced content",
                )
                assertFalse(
                    actual = sentinel in large,
                    message = "${scenario.name} result retained replaced content",
                )
            }
        } finally {
            deleteRecursively(root)
        }
    }

    test("round trips failed patch tool event") {
        val patchText = """
                *** Begin Patch
                *** Delete File: missing.txt
                *** End Patch
                """.trimIndent()
        val event = StablePatchToolEvent(
            callId = "call_apply_patch",
            diff = patchText.parsePatch(),
            result = StablePatchToolExecutionResult.Failure("File does not exist: missing.txt"),
        )

        val encoded = json.encodeToString(event)
        val element = json.parseToJsonElement(encoded).jsonObject

        assertEquals(JsonPrimitive("failure"), element["result"]?.jsonObject?.get("type"))
        assertEquals(event, json.decodeFromString<StablePatchToolEvent>(encoded))
        assertEquals(
            ResponseItem.CustomToolCallOutput(
                callId = event.callId,
                output = FunctionCallOutputPayload(
                    body = FunctionCallOutputBody.Text("File does not exist: missing.txt"),
                    success = false,
                ),
            ),
            event.toResponseHistoryItems().last(),
        )
    }
}

private fun successfulPatchEvent(
    patch: Patch = updatePatch(),
    applyResult: PatchApplyResult = PatchApplyResult(
        PatchAffectedPaths(
            added = emptyList(),
            modified = listOf("large.txt"),
            deleted = emptyList(),
        ),
    ),
): StablePatchToolEvent =
    StablePatchToolEvent(
        callId = "call_apply_patch",
        diff = patch,
        result = StablePatchToolExecutionResult.Success(applyResult),
    )

private fun updatePatch(): Patch =
    """
        *** Begin Patch
        *** Update File: large.txt
        @@
        -old
        +new
        *** End Patch
    """.trimIndent().parsePatch()

private fun snapshotScenarios(): List<SnapshotScenario> = listOf(
    SnapshotScenario(
        name = "delete",
        patch = """
            *** Begin Patch
            *** Delete File: target.txt
            *** End Patch
        """.trimIndent().parsePatch(),
        prepare = { root, targetContent ->
            SystemCoroutineFileSystem.writeString(Path(root, "target.txt"), targetContent)
        },
    ),
    SnapshotScenario(
        name = "add-overwrite",
        patch = """
            *** Begin Patch
            *** Add File: target.txt
            +replacement
            *** End Patch
        """.trimIndent().parsePatch(),
        prepare = { root, targetContent ->
            SystemCoroutineFileSystem.writeString(Path(root, "target.txt"), targetContent)
        },
    ),
    SnapshotScenario(
        name = "move-overwrite",
        patch = """
            *** Begin Patch
            *** Update File: source.txt
            *** Move to: target.txt
            @@
            -old
            +new
            *** End Patch
        """.trimIndent().parsePatch(),
        prepare = { root, targetContent ->
            SystemCoroutineFileSystem.writeString(Path(root, "source.txt"), "old\n")
            SystemCoroutineFileSystem.writeString(Path(root, "target.txt"), targetContent)
        },
    ),
)

private suspend fun Patch.applyAndSerialize(root: Path): String {
    val result = applyToFileSystem(root, SystemCoroutineFileSystem)
    return json.encodeToString(successfulPatchEvent(this, result))
}

private data class SnapshotScenario(
    val name: String,
    val patch: Patch,
    val prepare: suspend (root: Path, targetContent: String) -> Unit,
)

private suspend fun deleteRecursively(path: Path) {
    val metadata = SystemCoroutineFileSystem.metadataOrNull(path) ?: return
    if (metadata.isDirectory) {
        SystemCoroutineFileSystem.list(path).forEach { child -> deleteRecursively(child) }
    }
    SystemCoroutineFileSystem.delete(path, mustExist = false)
}
