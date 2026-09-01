package io.github.stream29.kodex.utils.filesystemlease

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.io.files.Path
import kotlinx.io.files.SystemTemporaryDirectory
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

public val fileSystemLeaseProcessTest by testSuite {
    test("coordinates readers and writers across processes") {
        val directory = Path(
            SystemTemporaryDirectory,
            "kodex-read-write-lease-process-${Random.nextLong()}",
        )
        SystemCoroutineFileSystem.createDirectories(directory)
        val process = ProcessBuilder(
            Path(System.getProperty("java.home"), "bin", "java").toString(),
            "-cp",
            System.getProperty("java.class.path"),
            "io.github.stream29.kodex.utils.filesystemlease.FileSystemLeaseProcessTestKt",
            directory.toString(),
        ).redirectErrorStream(true).start()
        try {
            assertEquals(
                ProcessReadyLine,
                process.inputStream.bufferedReader().readLine(),
            )
            runBlocking {
                val reader = FileSystemReadLease(directory)
                try {
                    assertFailsWith<FileSystemLeaseInUseException> {
                        FileSystemWriteLease(directory)
                    }
                } finally {
                    reader.close()
                    reader.coroutineContext.job.join()
                }
            }
        } finally {
            process.destroyForcibly()
            assertTrue(process.waitFor(5L, TimeUnit.SECONDS))
            deleteRecursivelyJvm(directory)
        }
    }
}

public fun main(arguments: Array<String>): Unit = runBlocking {
    FileSystemReadLease(Path(arguments.single()))
    println(ProcessReadyLine)
    System.out.flush()
    awaitCancellation()
}

private suspend fun deleteRecursivelyJvm(path: Path) {
    val metadata = SystemCoroutineFileSystem.metadataOrNull(path) ?: return
    if (metadata.isDirectory) {
        SystemCoroutineFileSystem.list(path).forEach { child -> deleteRecursivelyJvm(child) }
    }
    SystemCoroutineFileSystem.delete(path, mustExist = false)
}

private const val ProcessReadyLine: String = "acquired"
