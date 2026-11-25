package io.github.stream29.kodex.backend.host

import de.infix.testBalloon.framework.core.TestCompartment
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.utils.kotlinxiocoroutines.CoroutineFileSystem
import io.github.stream29.kodex.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.io.IOException
import kotlinx.io.files.Path
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private val fs = SystemCoroutineFileSystem

val bundledHomeGuideTest by testSuite(compartment = { TestCompartment.RealTime }) {
    test("a new Home gets the kodex skill and subsequent starts do not rewrite it") {
        withGuideHome { home, target ->
            assertTrue(ensureBundledHomeGuide(home))
            assertCurrentGuide(target)
            val fingerprint = fs.fingerprintOrNull(target)
            val noWrite = object : CoroutineFileSystem by fs {
                override suspend fun writeBytes(path: Path, content: ByteArray, append: Boolean, mustCreate: Boolean) {
                    error("An already-current guide must not create a temporary file.")
                }
            }
            assertTrue(ensureBundledHomeGuide(home, noWrite))
            assertEquals(fingerprint, fs.fingerprintOrNull(target))
        }
    }

    test("Backend installs the kodex skill before Ready on a fresh Home") {
        withHost(useCurrentCatalogFixture = false) { home, _, _ ->
            assertCurrentGuide(Path(home, "skills", "kodex", "SKILL.md"))
            assertFalse(fs.exists(Path(home, "skills", "kodex-home")))
        }
    }

    test("unknown same-size contents and non-regular paths are preserved with their siblings") {
        withGuideHome { home, target ->
            val directory = requireNotNull(target.parent)
            fs.createDirectories(directory)
            val sibling = Path(directory, "user-notes.md")
            val custom = "x".repeat(currentKodexGuideText.encodeToByteArray().size)
            fs.writeString(target, custom)
            fs.writeString(sibling, "keep me")
            assertFalse(ensureBundledHomeGuide(home))
            assertEquals(custom, fs.readString(target))
            fs.delete(target)
            fs.createDirectories(target)
            assertFalse(ensureBundledHomeGuide(home))
            assertTrue(fs.metadataOrNull(target)?.isDirectory == true)
            assertEquals("keep me", fs.readString(sibling))
            assertEquals(setOf("SKILL.md", "user-notes.md"), fs.list(directory).map { it.name }.toSet())
        }
    }

    test("failed publication and cancellation remove the task's staged file") {
        withGuideHome { home, target ->
            val failure = object : CoroutineFileSystem by fs {
                override suspend fun atomicMove(source: Path, destination: Path) {
                    throw IOException("fixture cannot publish guide")
                }
            }
            assertFailsWith<IOException> { ensureBundledHomeGuide(home, failure) }
            assertTrue(fs.list(requireNotNull(target.parent)).isEmpty())
            val cancellation = object : CoroutineFileSystem by fs {
                override suspend fun writeBytes(path: Path, content: ByteArray, append: Boolean, mustCreate: Boolean) {
                    fs.writeBytes(path, content, append, mustCreate)
                    throw CancellationException("fixture cancelled after staging")
                }
            }
            assertFailsWith<CancellationException> { ensureBundledHomeGuide(home, cancellation) }
            assertTrue(fs.list(requireNotNull(target.parent)).isEmpty())
        }
    }

    test("a custom file created during staging wins over the installer") {
        withGuideHome { home, target ->
            val interveningEdit = object : CoroutineFileSystem by fs {
                override suspend fun writeBytes(path: Path, content: ByteArray, append: Boolean, mustCreate: Boolean) {
                    fs.writeBytes(path, content, append, mustCreate)
                    fs.writeString(target, "saved user edit")
                }
            }
            assertFalse(ensureBundledHomeGuide(home, interveningEdit))
            assertEquals("saved user edit", fs.readString(target))
            assertEquals(listOf("SKILL.md"), fs.list(requireNotNull(target.parent)).map { it.name })
        }
    }
}

private suspend fun assertCurrentGuide(target: Path) {
    val content = fs.readString(target)
    assertTrue("name: kodex\n" in content)
    assertTrue("kodex agent sql --schema" in content)
    assertTrue("kodex backend status" in content)
    assertTrue("no `sessions/` JSON file mirror is written" in content)
}

private suspend fun withGuideHome(block: suspend (Path, Path) -> Unit) {
    val home = Path(SystemTemporaryDirectory, "kodex-bundled-guide-${Random.nextLong()}")
    fs.createDirectories(home, mustCreate = true)
    try {
        block(home, Path(home, "skills", "kodex", "SKILL.md"))
    } finally {
        withContext(NonCancellable) { removeGuideTree(home) }
    }
}

private suspend fun removeGuideTree(path: Path) {
    val metadata = fs.metadataOrNull(path) ?: return
    if (metadata.isDirectory) fs.list(path).forEach { removeGuideTree(it) }
    fs.delete(path, mustExist = false)
}
