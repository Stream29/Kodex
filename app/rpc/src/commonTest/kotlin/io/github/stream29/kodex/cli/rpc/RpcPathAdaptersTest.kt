@file:OptIn(kotlinx.rpc.internal.utils.ExperimentalRpcApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.stream29.kodex.cli.rpc

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentcontext.contract.ContextPathComparison
import io.github.stream29.kodex.agentcontext.contract.ContextPathEvaluation
import io.github.stream29.kodex.app.pathpicker.createDirectoryPickerViewModel
import io.github.stream29.kodex.app.pathpicker.contract.DirectoryPickerDependencies
import io.github.stream29.kodex.app.pathpicker.contract.DirectoryPickerEffect
import io.github.stream29.kodex.app.pathpicker.contract.DirectoryPickerFailure
import io.github.stream29.kodex.app.pathpicker.contract.DirectoryPickerLoadResult
import io.github.stream29.kodex.app.pathpicker.contract.DirectoryPickerLoadState
import io.github.stream29.kodex.app.pathpicker.contract.DirectoryPickerValidationResult
import io.github.stream29.kodex.rpc.contract.GlobalRpc
import io.github.stream29.kodex.rpc.models.BackendChildDirectory
import io.github.stream29.kodex.rpc.models.ContextPathQuery
import io.github.stream29.kodex.rpc.models.DirectoryListResult
import io.github.stream29.kodex.rpc.models.DirectoryQuery
import io.github.stream29.kodex.rpc.models.DirectoryQueryFailure
import io.github.stream29.kodex.rpc.models.DirectoryValidationResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlinx.io.files.Path
import kotlinx.rpc.RpcCall
import kotlinx.rpc.RpcClient
import kotlinx.rpc.withService
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

val rpcPathAdaptersTest by testSuite {
    test("POSIX Windows UNC strings and server display metadata survive both directory calls") {
        val fixtures = listOf(
            "/fixture/资料 with spaces", "C:\\work\\资料 with spaces", "C:/work/mixed/slashes",
            "\\\\server\\share\\资料 with spaces", "C:\\", "/",
        )
        for (spelling in fixtures) {
            val requests = mutableListOf<DirectoryQuery>()
            val child = "$spelling-entry-token"
            val parent = "$spelling-parent-token"
            val rpc = pathTestRpc { call ->
                requests += call.parameters.single() as DirectoryQuery
                when (call.callableName) {
                    "validateDirectory" -> DirectoryValidationResult.Directory(spelling)
                    "listDirectories" -> DirectoryListResult.Directories(
                        spelling, parent, listOf(BackendChildDirectory(child, "backend DISPLAY 名称")),
                    )
                    else -> error("Unexpected call")
                }
            }
            val browser = RpcDirectoryPickerBrowser(rpc, "C:\\captured-launch-not-browser-cwd")
            val validated = assertIs<DirectoryPickerValidationResult.Success>(browser.validate(Path(spelling)))
            assertEquals(spelling, validated.directory.toString())
            val listing = assertIs<DirectoryPickerLoadResult.Success>(browser.load(Path(spelling))).listing
            assertEquals(spelling, listing.directory.toString())
            assertEquals(parent, listing.parentDirectory.toString())
            assertEquals(listOf(child), listing.children.map { it.toString() })
            assertEquals("backend DISPLAY 名称", listing.childNames.getValue(listing.children.single()))
            assertEquals(List(2) { DirectoryQuery(spelling, "C:\\captured-launch-not-browser-cwd") }, requests)
        }
    }

    test("listing order and parent are not recomputed by the frontend OS") {
        val rpc = pathTestRpc {
            DirectoryListResult.Directories(
                "opaque-directory", "backend-parent-token",
                listOf(BackendChildDirectory("z-token", "A"), BackendChildDirectory("a-token", "Z")),
            )
        }
        val listing = assertIs<DirectoryPickerLoadResult.Success>(
            RpcDirectoryPickerBrowser(rpc, "captured-base").load(Path("opaque-request")),
        ).listing
        assertEquals(listOf("z-token", "a-token"), listing.children.map { it.toString() })
        assertEquals("backend-parent-token", listing.parentDirectory.toString())
    }

    test("lossy platform Path carriers fail safely instead of delivering changed backend spelling") {
        for (spelling in listOf("C:/", "/fixture/trailing/")) {
            val rpc = pathTestRpc { call ->
                if (call.callableName == "validateDirectory") DirectoryValidationResult.Directory(spelling)
                else DirectoryListResult.Directories("/fixture", null, listOf(BackendChildDirectory(spelling, "root")))
            }
            val browser = RpcDirectoryPickerBrowser(rpc, "/launch")
            if (Path(spelling).toString() != spelling) {
                assertIs<DirectoryPickerValidationResult.Failure>(browser.validate(Path("/request")))
                assertIs<DirectoryPickerLoadResult.Failure>(browser.load(Path("/request")))
            } else {
                assertEquals(spelling, assertIs<DirectoryPickerValidationResult.Success>(
                    browser.validate(Path("/request")),
                ).directory.toString())
            }
        }
    }

    test("all typed directory failures map safely and neither failure path makes extra RPCs") {
        for (reason in DirectoryQueryFailure.entries) {
            val calls = mutableListOf<String>()
            val rpc = pathTestRpc { call ->
                calls += call.callableName
                if (call.callableName == "validateDirectory") DirectoryValidationResult.Failure(reason, "parent-token")
                else DirectoryListResult.Failure(reason, "parent-token")
            }
            val browser = RpcDirectoryPickerBrowser(rpc, "/captured")
            val validation = assertIs<DirectoryPickerValidationResult.Failure>(browser.validate(Path("requested")))
            val listing = assertIs<DirectoryPickerLoadResult.Failure>(browser.load(Path("requested")))
            assertEquals(listOf("validateDirectory", "listDirectories"), calls)
            assertEquals("parent-token", validation.parentDirectory.toString())
            assertEquals(validation.failure, listing.failure)
            when (reason) {
                DirectoryQueryFailure.NotDirectory ->
                    assertEquals(Path("requested"), assertIs<DirectoryPickerFailure.NotDirectory>(validation.failure).directory)
                DirectoryQueryFailure.HomeDirectoryUnavailable ->
                    assertSame(DirectoryPickerFailure.HomeDirectoryUnavailable, validation.failure)
                else -> assertIs<DirectoryPickerFailure.FileSystem>(validation.failure)
            }
        }
    }

    test("directory and Context cancellation transport failures and Errors are not swallowed") {
        for (failure in listOf(CancellationException("cancelled"), IOException("transport"), AssertionError("bug"))) {
            val rpc = pathTestRpc { throw failure }
            val browser = RpcDirectoryPickerBrowser(rpc, "/captured")
            val policy = RpcContextSourcePathPolicy(rpc, "/captured")
            for (operation in listOf<suspend () -> Any>(
                { browser.validate(Path("/requested")) },
                { browser.load(Path("/requested")) },
                { policy.evaluate("~", emptyList()) },
            )) {
                val actual = when (failure) {
                    is CancellationException -> assertFailsWith<CancellationException> { operation() }
                    is IOException -> assertFailsWith<IOException> { operation() }
                    else -> assertFailsWith<AssertionError> { operation() }
                }
                assertSame(failure, actual)
            }
        }
    }

    test("Context forwards frozen spelling and original ordering without local normalization or merging") {
        runTest {
            val originals = mutableListOf("~\\kept", "C:relative", "\\\\host\\share\\资料", "/has\$dollar", "")
            val snapshot = originals.toList()
            val entered = CompletableDeferred<ContextPathQuery>()
            val reply = CompletableDeferred<ContextPathEvaluation>()
            val rpc = pathTestRpc { call ->
                assertEquals("evaluateContextPaths", call.callableName)
                entered.complete(call.parameters.single() as ContextPathQuery)
                reply.await()
            }
            val policy = RpcContextSourcePathPolicy(rpc, "C:\\launch")
            val pending = async { policy.evaluate("  ~/original spelling  ", originals) }
            val query = entered.await()
            originals.clear()
            assertEquals(snapshot, query.originalPaths)
            assertEquals("  ~/original spelling  ", query.candidate)
            assertEquals("C:\\launch", query.launchBaseDirectory)
            val evaluation = ContextPathEvaluation.Evaluated(
                ContextPathComparison("/backend-key", false),
                snapshot.map { ContextPathComparison(null, false) },
            )
            reply.complete(evaluation)
            assertSame(evaluation, pending.await())
        }
    }

    test("same shared picker confirms while list waits and ignores older list and confirmation replies") {
        runTest {
            val listA = CompletableDeferred<DirectoryListResult>()
            val listB = CompletableDeferred<DirectoryListResult>()
            val validateA = CompletableDeferred<DirectoryValidationResult>()
            val validateB = CompletableDeferred<DirectoryValidationResult>()
            val calls = mutableListOf<String>()
            val rpc = pathTestRpc { call ->
                val query = call.parameters.single() as DirectoryQuery
                calls += "${call.callableName}:${query.path}"
                when (call.callableName to query.path) {
                    "listDirectories" to "/a" -> listA.await()
                    "listDirectories" to "/b" -> listB.await()
                    "validateDirectory" to "/a" -> validateA.await()
                    "validateDirectory" to "/b" -> validateB.await()
                    else -> error("Unexpected query")
                }
            }
            val browser = RpcDirectoryPickerBrowser(rpc, "/captured-launch")
            val picker = createDirectoryPickerViewModel(Path("/a"), object : DirectoryPickerDependencies {
                override val browser = browser
            }, this)
            val effects = mutableListOf<DirectoryPickerEffect>()
            val collecting = launch { picker.effects.toList(effects) }
            try {
                runCurrent()
                picker.confirm()
                runCurrent()
                assertTrue("validateDirectory:/a" in calls)
                assertFalse(listA.isCompleted)
                picker.navigateTo(Path("/b"))
                runCurrent()
                listB.complete(DirectoryListResult.Directories("/b", "/server-parent", emptyList()))
                runCurrent()
                listA.complete(DirectoryListResult.Directories("/a", null, emptyList()))
                validateA.complete(DirectoryValidationResult.Directory("/obsolete-a"))
                runCurrent()
                assertEquals("/b", assertIs<DirectoryPickerLoadState.Ready>(
                    picker.state.value.loadState,
                ).directory.toString())
                assertTrue(effects.isEmpty())
                picker.confirm()
                runCurrent()
                validateB.complete(DirectoryValidationResult.Directory("/confirmed-b"))
                runCurrent()
                assertEquals<List<DirectoryPickerEffect>>(
                    listOf(DirectoryPickerEffect.DirectorySelected(Path("/confirmed-b"))), effects,
                )
            } finally {
                picker.close()
                collecting.cancel()
            }
        }
    }

    test("picker close cancels its RPC wait and cannot produce a stale selection without a live owner") {
        runTest {
            val pending = CompletableDeferred<DirectoryValidationResult>()
            val validationEnded = CompletableDeferred<Unit>()
            val rpc = pathTestRpc { call ->
                if (call.callableName == "listDirectories") {
                    DirectoryListResult.Failure(DirectoryQueryFailure.FileSystem)
                } else try {
                    pending.await()
                } finally {
                    validationEnded.complete(Unit)
                }
            }
            val browser = RpcDirectoryPickerBrowser(rpc, "/captured")
            val picker = createDirectoryPickerViewModel(Path("/a"), object : DirectoryPickerDependencies {
                override val browser = browser
            }, this)
            val effects = mutableListOf<DirectoryPickerEffect>()
            val collecting = launch { picker.effects.toList(effects) }
            runCurrent()
            picker.confirm()
            runCurrent()
            picker.close()
            runCurrent()
            assertTrue(validationEnded.isCompleted)
            pending.complete(DirectoryValidationResult.Directory("/too-late"))
            picker.confirm()
            runCurrent()
            assertTrue(effects.isEmpty())
            collecting.cancel()
        }
    }

    test("failed child listing still permits independent Select and listing itself never selects") {
        runTest {
            val calls = mutableListOf<String>()
            val rpc = pathTestRpc { call ->
                calls += call.callableName
                when (call.callableName) {
                    "listDirectories" -> DirectoryListResult.Failure(DirectoryQueryFailure.FileSystem)
                    "validateDirectory" -> DirectoryValidationResult.Directory("/confirmed")
                    else -> error("Path adapters must not call mutations or resolve a Current Session")
                }
            }
            val browser = RpcDirectoryPickerBrowser(rpc, "/captured")
            val picker = createDirectoryPickerViewModel(Path("/requested"), object : DirectoryPickerDependencies {
                override val browser = browser
            }, this)
            val effects = mutableListOf<DirectoryPickerEffect>()
            val collecting = launch { picker.effects.toList(effects) }
            try {
                runCurrent()
                assertIs<DirectoryPickerLoadState.Failed>(picker.state.value.loadState)
                assertTrue(effects.isEmpty())
                picker.confirm()
                runCurrent()
                assertEquals(listOf("listDirectories", "validateDirectory"), calls)
                assertEquals<List<DirectoryPickerEffect>>(
                    listOf(DirectoryPickerEffect.DirectorySelected(Path("/confirmed"))), effects,
                )
            } finally {
                picker.close()
                collecting.cancel()
            }
        }
    }
}

private fun pathTestRpc(handler: suspend (RpcCall) -> Any?): GlobalRpc = object : RpcClient {
    @Suppress("UNCHECKED_CAST")
    override suspend fun <T> call(call: RpcCall): T = handler(call) as T
    override fun <T> callServerStreaming(call: RpcCall): Flow<T> = error("Unexpected streaming call")
}.withService<GlobalRpc>()
