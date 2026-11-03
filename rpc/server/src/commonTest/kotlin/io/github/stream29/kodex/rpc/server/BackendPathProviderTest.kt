package io.github.stream29.kodex.rpc.server

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentcontext.contract.ContextPathEvaluation
import io.github.stream29.kodex.agentcontext.contract.ContextPathEvaluationFailure
import io.github.stream29.kodex.rpc.models.ContextPathQuery
import io.github.stream29.kodex.rpc.models.DirectoryListResult
import io.github.stream29.kodex.rpc.models.DirectoryQuery
import io.github.stream29.kodex.rpc.models.DirectoryQueryFailure
import io.github.stream29.kodex.rpc.models.DirectoryValidationResult
import io.github.stream29.kodex.utils.kotlinxiocoroutines.CoroutineFileSystem
import io.github.stream29.kodex.utils.kotlinxiocoroutines.CoroutineRawSink
import io.github.stream29.kodex.utils.kotlinxiocoroutines.CoroutineRawSource
import io.github.stream29.kodex.utils.kotlinxiocoroutines.FileFingerprint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.io.IOException
import kotlinx.io.files.FileMetadata
import kotlinx.io.files.Path
import kotlinx.io.files.SystemPathSeparator
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

val backendPathProviderTest by testSuite {
    test("relative and platform home shorthand resolve only from explicit backend environment") {
        val f = PathProviderFixture()
        for ((spelling, expected) in listOf(
            "project" to Path(f.base, "project"),
            "" to f.base,
            "~" to f.home,
            "~${SystemPathSeparator}资料 with spaces" to Path(f.home, "资料 with spaces"),
            "~someone" to Path(f.base, "~someone"),
        )) {
            f.fs.directories += expected
            val result = assertIs<DirectoryValidationResult.Directory>(
                f.provider.validateDirectory(f.query(spelling)),
            )
            assertEquals(expected.toString(), result.path)
            assertEquals(expected, f.fs.resolved.last())
        }
        assertTrue(f.fs.resolved.all { it.isAbsolute })
        assertEquals(0, f.fs.listCalls)
    }

    test("direct children only sorted by backend name then path skipping files and dangling links") {
        val f = PathProviderFixture()
        val z = Path(f.base, "z")
        val lower = Path(f.base, "alpha")
        val upper = Path(f.base, "Alpha")
        val file = Path(f.base, "file")
        val dangling = Path(f.base, "dangling")
        val deep = Path(lower, "never-scanned")
        f.fs.directories += listOf(z, lower, upper, deep)
        f.fs.files += file
        f.fs.entries = listOf(z, file, lower, dangling, upper)
        val result = assertIs<DirectoryListResult.Directories>(f.provider.listDirectories(f.query()))
        val expected = listOf(z, lower, upper).sortedWith(
            compareBy<Path> { it.name.lowercase() }.thenBy { it.toString() },
        )
        assertEquals(expected.map { it.toString() }, result.children.map { it.path })
        assertEquals(expected.map { it.name }, result.children.map { it.name })
        assertEquals(f.base.parent?.toString(), result.parentDirectory)
        assertEquals(listOf(f.base), f.fs.listed)
        assertFalse(deep in f.fs.metadataReads)
    }

    test("empty listing is complete success but metadata permission errors never return partial children") {
        val f = PathProviderFixture()
        assertTrue(assertIs<DirectoryListResult.Directories>(
            f.provider.listDirectories(f.query()),
        ).children.isEmpty())
        val good = Path(f.base, "good")
        val denied = Path(f.base, "denied")
        f.fs.directories += listOf(good, denied)
        f.fs.entries = listOf(good, denied)
        f.fs.metadataFailure = { if (it == denied) throw IOException("private backend detail") }
        val failure = assertIs<DirectoryListResult.Failure>(f.provider.listDirectories(f.query()))
        assertEquals(DirectoryQueryFailure.FileSystem, failure.reason)
        assertFalse(failure.toString().contains("private backend detail"))
        assertIs<DirectoryValidationResult.Directory>(f.provider.validateDirectory(f.query()))
    }

    test("invalid base NUL missing Home and non-directory have independent safe classifications") {
        val f = PathProviderFixture()
        assertEquals(DirectoryQueryFailure.InvalidLaunchBase, assertIs<DirectoryValidationResult.Failure>(
            f.provider.validateDirectory(DirectoryQuery(f.base.toString(), "relative-base")),
        ).reason)
        assertEquals(DirectoryQueryFailure.InvalidPath, assertIs<DirectoryValidationResult.Failure>(
            f.provider.validateDirectory(f.query("bad\u0000path")),
        ).reason)
        val noHome = f.provider(home = null)
        assertEquals(DirectoryQueryFailure.HomeDirectoryUnavailable, assertIs<DirectoryValidationResult.Failure>(
            noHome.validateDirectory(f.query("~")),
        ).reason)
        assertEquals(0, f.fs.resolved.size)
        val missing = Path(f.base, "absent")
        val notDirectory = assertIs<DirectoryValidationResult.Failure>(
            f.provider.validateDirectory(f.query(missing.toString())),
        )
        assertEquals(DirectoryQueryFailure.NotDirectory, notDirectory.reason)
        assertEquals(f.base.toString(), notDirectory.parentDirectory)
        f.fs.files += missing
        assertEquals(DirectoryQueryFailure.NotDirectory, assertIs<DirectoryListResult.Failure>(
            f.provider.listDirectories(f.query(missing.toString())),
        ).reason)
    }

    test("resolve list and target metadata permission failures do not expose exception text") {
        for (operation in listOf("resolve", "metadata", "list")) {
            val f = PathProviderFixture()
            val denied = IOException("private path and OS permission detail")
            when (operation) {
                "resolve" -> f.fs.resolveFailure = denied
                "metadata" -> f.fs.metadataFailure = { throw denied }
                "list" -> f.fs.listing = { throw denied }
            }
            val result = assertIs<DirectoryListResult.Failure>(f.provider.listDirectories(f.query()))
            assertEquals(DirectoryQueryFailure.FileSystem, result.reason)
            assertFalse(result.toString().contains("private path"))
        }
    }

    test("blocked list does not serialize validation and cancelling list leaves validation usable") {
        coroutineScope {
            val f = PathProviderFixture()
            val entered = CompletableDeferred<Unit>()
            val waiting = CompletableDeferred<Collection<Path>>()
            f.fs.listing = {
                entered.complete(Unit)
                waiting.await()
            }
            val listing = async { f.provider.listDirectories(f.query()) }
            entered.await()
            assertIs<DirectoryValidationResult.Directory>(f.provider.validateDirectory(f.query()))
            listing.cancel()
            assertFailsWith<CancellationException> { listing.await() }
            assertIs<DirectoryValidationResult.Directory>(f.provider.validateDirectory(f.query()))
        }
    }

    test("cancellation and nonrecoverable errors propagate unchanged at every IO boundary") {
        for (operation in listOf("resolve", "metadata", "list")) {
            for (error in listOf(CancellationException("cancelled"), AssertionError("bug"))) {
                val f = PathProviderFixture()
                when (operation) {
                    "resolve" -> f.fs.resolveFailure = error
                    "metadata" -> f.fs.metadataFailure = { throw error }
                    "list" -> f.fs.listing = { throw error }
                }
                val caught = if (error is CancellationException) {
                    assertFailsWith<CancellationException> { f.provider.listDirectories(f.query()) }
                } else {
                    assertFailsWith<AssertionError> { f.provider.listDirectories(f.query()) }
                }
                assertSame(error, caught)
                currentCoroutineContext().ensureActive()
            }
        }
    }

    test("wire and work budgets reject oversized observations rather than truncate successful lists") {
        val f = PathProviderFixture()
        f.fs.entries = (1..100).map { Path(f.base, "directory-$it") }
        f.fs.directories += f.fs.entries
        val bounded = f.provider(budget = 4096)
        assertIs<DirectoryValidationResult.Directory>(bounded.validateDirectory(f.query()))
        assertEquals(DirectoryQueryFailure.LimitExceeded, assertIs<DirectoryListResult.Failure>(
            bounded.listDirectories(f.query()),
        ).reason)
        val huge = "x".repeat(4096)
        assertEquals(DirectoryQueryFailure.LimitExceeded, assertIs<DirectoryValidationResult.Failure>(
            bounded.validateDirectory(f.query(huge)),
        ).reason)
        assertEquals(ContextPathEvaluationFailure.LimitExceeded, assertIs<ContextPathEvaluation.Failure>(
            bounded.evaluateContextPaths(ContextPathQuery("~/$huge", emptyList(), f.base.toString())),
        ).reason)
    }

    test("Context uses actual injected roots and original lexical rules without any filesystem call") {
        val f = PathProviderFixture()
        val original = listOf(
            "~/custom", Path(f.home, "custom").toString(), "", "relative", "\$ENV",
            "${f.base}/has\$dollar", "~someone", "~/../custom", "~/Case", "~/case",
            "~", "~/missing", "~/bad\u0000path",
        )
        val snapshot = original.toList()
        val result = assertIs<ContextPathEvaluation.Evaluated>(f.provider.evaluateContextPaths(
            ContextPathQuery(f.kodex.toString(), original, f.base.toString()),
        ))
        assertTrue(result.candidate.matchesBuiltIn)
        assertEquals(f.kodex.toString(), result.candidate.lexicalKey)
        assertEquals(original.size, result.originalPaths.size)
        assertEquals(result.originalPaths[0].lexicalKey, result.originalPaths[1].lexicalKey)
        for (index in listOf(2, 3, 4, 5, 6, 12)) assertNull(result.originalPaths[index].lexicalKey)
        assertEquals(Path(f.home, "../custom").toString(), result.originalPaths[7].lexicalKey)
        assertFalse(result.originalPaths[8].lexicalKey == result.originalPaths[9].lexicalKey)
        assertFalse(result.originalPaths[10].matchesBuiltIn)
        assertEquals(Path(f.home, "missing").toString(), result.originalPaths[11].lexicalKey)
        assertEquals(snapshot, original)
        assertTrue(f.fs.resolved.isEmpty())
        assertTrue(f.fs.metadataReads.isEmpty())
        assertEquals(0, f.fs.listCalls)
        for (root in listOf(f.agents, f.kodex, f.codex)) {
            assertTrue(assertIs<ContextPathEvaluation.Evaluated>(f.provider.evaluateContextPaths(
                ContextPathQuery(root.toString(), emptyList(), f.base.toString()),
            )).candidate.matchesBuiltIn)
        }
        // Default user-home spelling must not impersonate the overridden actual Kodex root.
        assertFalse(assertIs<ContextPathEvaluation.Evaluated>(f.provider.evaluateContextPaths(
            ContextPathQuery("~/.kodex", emptyList(), f.base.toString()),
        )).candidate.matchesBuiltIn)
    }

    test("Context environment failure and invalid base never scan roots") {
        val f = PathProviderFixture()
        assertEquals(ContextPathEvaluationFailure.EnvironmentUnavailable, assertIs<ContextPathEvaluation.Failure>(
            f.provider(home = null).evaluateContextPaths(ContextPathQuery("~", emptyList(), f.base.toString())),
        ).reason)
        assertEquals(ContextPathEvaluationFailure.InvalidLaunchBase, assertIs<ContextPathEvaluation.Failure>(
            f.provider.evaluateContextPaths(ContextPathQuery("~", emptyList(), "relative")),
        ).reason)
        assertTrue(f.fs.metadataReads.isEmpty())
    }

    test("runtime OS lexical fixtures include POSIX drive UNC dots unicode and case without real IO") {
        val f = PathProviderFixture()
        val isWindows = Path("C:\\").isAbsolute
        val absoluteSpellings = if (isWindows) {
            listOf("C:\\资料 with spaces\\child", "C:/mixed/slashes", "\\\\server\\share\\child",
                "C:\\a\\.\\b\\..\\c", "C:\\Case", "C:\\case")
        } else {
            listOf("/资料 with spaces/child", "/a/./b/../c", "/Case", "/case", "//server/share/child")
        }
        for (spelling in absoluteSpellings) {
            val path = Path(spelling)
            f.fs.directories += path
            assertEquals(path.toString(), assertIs<DirectoryValidationResult.Directory>(
                f.provider.validateDirectory(f.query(spelling)),
            ).path)
            val evaluation = assertIs<ContextPathEvaluation.Evaluated>(f.provider.evaluateContextPaths(
                ContextPathQuery(spelling, emptyList(), f.base.toString()),
            ))
            assertEquals(path.toString(), evaluation.candidate.lexicalKey)
        }
        for (spelling in listOf("C:drive-relative", "relative/child")) {
            assertNull(assertIs<ContextPathEvaluation.Evaluated>(f.provider.evaluateContextPaths(
                ContextPathQuery(spelling, emptyList(), f.base.toString()),
            )).candidate.lexicalKey)
        }
        // Root-relative syntax retains the runtime Path rule, not a new browser-inferred rule.
        val rootRelative = "\\root-relative"
        assertEquals(Path(rootRelative).takeIf { it.isAbsolute }?.toString(),
            assertIs<ContextPathEvaluation.Evaluated>(f.provider.evaluateContextPaths(
                ContextPathQuery(rootRelative, emptyList(), f.base.toString()),
            )).candidate.lexicalKey)
    }

    if (Path("C:\\").isAbsolute) {
        test("Windows relative queries anchor drive state to launch base not daemon process state") {
            val f = PathProviderFixture()
            for ((spelling, expected) in listOf(
                "C:child" to Path(f.base, "child"),
                "\\root-relative" to Path("C:\\root-relative"),
                "/root-relative" to Path("C:/root-relative"),
            )) {
                f.fs.directories += expected
                assertEquals(expected.toString(), assertIs<DirectoryValidationResult.Directory>(
                    f.provider.validateDirectory(f.query(spelling)),
                ).path)
            }
            assertEquals(DirectoryQueryFailure.InvalidPath, assertIs<DirectoryValidationResult.Failure>(
                f.provider.validateDirectory(f.query("D:unknown-drive-current-directory")),
            ).reason)
            assertEquals(DirectoryQueryFailure.InvalidLaunchBase, assertIs<DirectoryValidationResult.Failure>(
                f.provider.validateDirectory(DirectoryQuery("child", "\\not-fully-qualified-base")),
            ).reason)
            val uncBase = Path("\\\\server\\share\\launch")
            val uncChild = Path(uncBase, "资料")
            f.fs.directories += uncChild
            assertEquals(uncChild.toString(), assertIs<DirectoryValidationResult.Directory>(
                f.provider.validateDirectory(DirectoryQuery("资料", uncBase.toString())),
            ).path)
            assertEquals(DirectoryQueryFailure.InvalidPath, assertIs<DirectoryValidationResult.Failure>(
                f.provider.validateDirectory(DirectoryQuery("\\unknown-launch-drive", uncBase.toString())),
            ).reason)
        }
    }
}

private class PathProviderFixture {
    private val root = if (Path("C:\\").isAbsolute) Path("C:\\parity-fixture") else Path("/parity-fixture")
    val base = Path(root, "launch")
    val home = Path(root, "os-home")
    val agents = Path(root, "actual-agents")
    val kodex = Path(root, "overridden-kodex")
    val codex = Path(root, "actual-codex")
    val fs = PathProviderFakeFileSystem().also { it.directories += base }
    val provider = provider()
    fun query(path: String = base.toString()) = DirectoryQuery(path, base.toString())
    fun provider(home: Path? = this.home, budget: Long = 8L * 1024 * 1024) =
        BackendPathProvider(fs, home, agents, kodex, codex, budget)
}

/** No delegation to a system FS: an unexpected method fails instead of touching user data. */
private class PathProviderFakeFileSystem : CoroutineFileSystem {
    val directories = mutableSetOf<Path>()
    val files = mutableSetOf<Path>()
    val resolved = mutableListOf<Path>()
    val metadataReads = mutableListOf<Path>()
    val listed = mutableListOf<Path>()
    val listCalls: Int get() = listed.size
    var entries: Collection<Path> = emptyList()
    var resolveFailure: Throwable? = null
    var metadataFailure: (Path) -> Unit = {}
    var listing: suspend () -> Collection<Path> = { entries }
    override suspend fun resolve(path: Path): Path {
        check(path.isAbsolute) { "Provider must never depend on a daemon cwd." }
        resolved += path
        resolveFailure?.let { throw it }
        return path
    }
    override suspend fun metadataOrNull(path: Path): FileMetadata? {
        metadataReads += path
        metadataFailure(path)
        return when (path) {
            in directories -> FileMetadata(isRegularFile = false, isDirectory = true, size = -1L)
            in files -> FileMetadata(isRegularFile = true, isDirectory = false, size = 0L)
            else -> null
        }
    }
    override suspend fun list(directory: Path): Collection<Path> {
        listed += directory
        return listing()
    }
    override suspend fun exists(path: Path): Boolean = error("Unexpected exists")
    override suspend fun delete(path: Path, mustExist: Boolean): Unit = error("Unexpected delete")
    override suspend fun createDirectories(path: Path, mustCreate: Boolean): Unit = error("Unexpected create")
    override suspend fun atomicMove(source: Path, destination: Path): Unit = error("Unexpected move")
    override suspend fun fingerprintOrNull(path: Path): FileFingerprint? = error("Unexpected fingerprint")
    override suspend fun <R> useSource(path: Path, block: suspend (CoroutineRawSource) -> R): R =
        error("Unexpected source")
    override suspend fun <R> useSink(
        path: Path,
        append: Boolean,
        mustCreate: Boolean,
        block: suspend (CoroutineRawSink) -> R,
    ): R = error("Unexpected sink")
}
