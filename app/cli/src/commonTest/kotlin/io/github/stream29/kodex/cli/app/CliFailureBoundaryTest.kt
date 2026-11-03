package io.github.stream29.kodex.cli.app

import de.infix.testBalloon.framework.core.testSuite
import kotlinx.coroutines.CancellationException
import kotlin.test.assertEquals
import kotlin.test.assertSame

val cliFailureBoundaryTest by testSuite {
    test("successful invocation does not log or print") {
        assertEquals(17, runCliSafely(false, { 42 }, { error("unexpected report") }, { error("unexpected output") }) { 17 })
    }

    test("failure is reported with original cause and existing terminal diagnostic") {
        val cause = IllegalArgumentException("cause marker")
        val failure = IllegalStateException("failure marker", cause)
        var reported: Throwable? = null
        val output = mutableListOf<String>()
        val code = runCliSafely(false, { 42 }, { reported = it }, { output += it }) { throw failure }
        assertEquals(1, code)
        assertSame(failure, reported)
        assertSame(cause, reported?.cause)
        assertEquals(listOf("Unable to run Kodex: failure marker"), output)
    }

    test("backend startup retains pid diagnostic even when logging fails") {
        val output = mutableListOf<String>()
        val code = runCliSafely(true, { 42 }, { error("logger unavailable") }, { output += it }) {
            throw IllegalStateException("startup failed")
        }
        assertEquals(1, code)
        assertEquals(listOf("Unable to run Kodex [pid=42]: startup failed"), output)
    }

    test("early initialization failure retains fallback diagnostic") {
        val output = mutableListOf<String>()
        val code = runCliSafely(false, { 42 }, { error("cannot initialize file logging") }, { output += it }) {
            throw IllegalStateException()
        }
        assertEquals(1, code)
        assertEquals(listOf("Unable to run Kodex: operation failed"), output)
    }

    test("cancellation remains status 130 without reporting or output") {
        val code = runCliSafely(false, { 42 }, { error("unexpected report") }, { error("unexpected output") }) {
            throw CancellationException("cancelled")
        }
        assertEquals(130, code)
    }

    test("all runtime invocation branches retain their failure boundary") {
        val cases = listOf(
            listOf("tui"),
            listOf("tui", "--backend-url", "http://127.0.0.1:18080"),
            listOf("web"),
            listOf("web", "--origin", "http://localhost:18795"),
            listOf("app"),
            listOf("host", "--origin", "http://localhost:18795"),
            listOf("backend", "start"), listOf("backend", "status"), listOf("backend", "stop"),
            listOf("backend", "reload"),
        )
        for (arguments in cases) {
            val failure = IllegalStateException("branch failure")
            val parserOutput = mutableListOf<Pair<String, Boolean>>()
            val output = mutableListOf<String>()
            var reported: Throwable? = null
            val code = dispatchCli(arguments, { msg, err -> parserOutput += msg to err }) { invocation ->
                runCliSafely(invocation is CliInvocation.BackendStart, { 42 }, { reported = it }, { output += it }) {
                    throw failure
                }
            }
            if (arguments.first() == "host") {
                assertEquals(listOf(HostCommandMigrationNotice to true), parserOutput, arguments.toString())
            } else {
                assertEquals(emptyList(), parserOutput, arguments.toString())
            }
            assertEquals(1, code, arguments.toString())
            assertSame(failure, reported, arguments.toString())
            val prefix = if (arguments == listOf("backend", "start")) "Unable to run Kodex [pid=42]: "
                else "Unable to run Kodex: "
            assertEquals(listOf(prefix + "branch failure"), output, arguments.toString())
        }
    }
}
