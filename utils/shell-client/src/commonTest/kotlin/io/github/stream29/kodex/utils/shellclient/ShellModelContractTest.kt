package io.github.stream29.kodex.utils.shellclient

import de.infix.testBalloon.framework.core.testSuite
import kotlinx.io.files.Path
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
val shellModelContractTest by testSuite {
    test("shell wire descriptor and explicit paths do not perform host discovery") {
        val selected = Shell(ShellType.PowerShell, Path("C:\\sentinel\\pwsh.exe"))
        assertEquals("Shell", Shell.Serializer.descriptor.serialName)
        assertEquals(Json.encodeToString(selected.path.toString()), Json.encodeToString(selected))
        assertEquals(selected, Json.decodeFromString<Shell>(Json.encodeToString(selected)))
        assertEquals(ShellType.PowerShell, selected.path.toString().shellTypeOrNull())
        assertFailsWith<SerializationException> {
            Json.decodeFromString<Shell>("\"/sentinel/unsupported\"")
        }
    }

    test("real command preserves portable defaults and environment validation") {
        val selected = Shell(ShellType.Sh, Path("/sentinel/sh"))
        val command = ShellProcessCommand("sentinel", shell = selected)
        assertEquals(selected, command.shell)
        assertEquals(Path("."), command.workingDirectory)
        assertEquals(emptyMap(), command.environment)
        assertFalse(command.login)
        assertFalse(command.tty)
        assertFailsWith<IllegalArgumentException> {
            ShellProcessCommand("sentinel", shell = selected, environment = mapOf("bad-name" to "value"))
        }
        assertFailsWith<IllegalArgumentException> {
            ShellProcessCommand("sentinel", shell = selected, environment = mapOf("VALID" to "NUL\u0000"))
        }
    }

    test("output slice retains cursor accounting and omission rendering") {
        val slice = StdoutSlice(
            head = "head".encodeToByteArray(),
            tail = "tail".encodeToByteArray(),
            omittedByteCount = 9uL,
            offset = 0uL,
            nextOffset = 17uL,
            totalByteCount = 20uL,
        )
        assertEquals(8uL, slice.retainedByteCount)
        assertEquals(17uL, slice.nextOffset - slice.offset)
        assertEquals("head\n... 9 bytes omitted ...\ntail", slice.renderedBytes().decodeToString())
        assertEquals(1_024 * 1_024, StdoutBufferDefaultQuotaByteCount)
        assertEquals(10, StdoutBufferHeadTailRetentionPercent)
    }
}
