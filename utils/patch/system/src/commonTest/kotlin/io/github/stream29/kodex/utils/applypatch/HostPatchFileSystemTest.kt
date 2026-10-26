package io.github.stream29.kodex.utils.applypatch

import de.infix.testBalloon.framework.core.testSuite
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.readString
import kotlin.random.Random
import kotlin.test.assertEquals

val hostPatchFileSystemTest by testSuite {
    test("host overload applies a patch with the system filesystem") {
        val root = Path(SystemTemporaryDirectory, "kodex-host-patch-${Random.nextLong()}")
        val file = Path(root, "created.txt")
        SystemFileSystem.createDirectories(root)
        try {
            """
                *** Begin Patch
                *** Add File: created.txt
                +你好 Wasm boundary
                *** End Patch
            """.trimIndent().parsePatch().applyToFileSystem(root)
            val source = SystemFileSystem.source(file).buffered()
            try {
                assertEquals("你好 Wasm boundary\n", source.readString())
            } finally {
                source.close()
            }
        } finally {
            if (SystemFileSystem.exists(file)) SystemFileSystem.delete(file)
            SystemFileSystem.delete(root)
        }
    }
}
