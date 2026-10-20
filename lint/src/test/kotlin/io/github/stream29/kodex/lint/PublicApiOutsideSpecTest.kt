package io.github.stream29.kodex.lint

import dev.detekt.api.Config
import dev.detekt.test.lint
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.TestFactory

class PublicApiOutsideSpecTest {
    @TestFactory
    fun declarations() = listOf(
        Case("public function", "public fun run() = Unit", 1),
        Case("implicit public function", "fun run() = Unit", 1),
        Case("public extension", "public fun String.run() = length", 1),
        Case("suspend function", "public suspend fun run() = Unit", 1),
        Case("inline function", "public inline fun run() = Unit", 1),
        Case("public property", "public val value = 1", 1),
        Case("implicit public property", "var value = 1", 1),
        Case("public class", "public class Service { fun run() {} }", 1),
        Case("public interface", "public interface Service { fun run() }", 1),
        Case("public object", "public object Service { fun run() {} }", 1),
        Case("public typealias", "public typealias Name = String", 1),
        Case("public expect outside spec", "public expect fun run(): Unit", 1),
        Case("internal function", "internal fun run() = Unit", 0),
        Case("private function", "private fun run() = Unit", 0),
        Case("internal property", "internal val value = 1", 0),
        Case("private property", "private val value = 1", 0),
        Case("internal typealias", "internal typealias Name = String", 0),
        Case("internal class override", "internal class Impl : Runnable { override fun run() {} }", 0),
        Case("private class public member", "private class Impl { public fun run() {} }", 0),
        Case("internal interface", "internal interface Service { fun run() }", 0),
        Case("internal nested tree", "internal object Host { public class Nested { fun run() {} } }", 0),
        Case("actual function", "public actual fun run() = Unit", 0),
        Case("actual property", "public actual val value = 1", 0),
        Case("actual typealias", "public actual typealias Name = String", 0),
        Case("actual class member", "public actual class Impl { public actual fun run() {} }", 0),
        Case("actual override", "actual class Impl : Port { actual override fun run() {} }", 0),
        Case("unmarked actual-class override", "actual class Impl : Port { override fun run() {} }", 1),
        Case("actual class extra member", "public actual class Impl { public fun extra() {} }", 1),
        Case("actual class implicit member", "public actual class Impl { fun extra() {} }", 1),
        Case("actual class internal member", "public actual class Impl { internal fun extra() {} }", 0),
        Case("actual nested class", "public actual class Host { public actual class Child }", 0),
        Case("extra nested class", "public actual class Host { public class Child }", 1),
        Case("actual companion", "public actual class Host { public actual companion object }", 0),
        Case("extra companion", "public actual class Host { public companion object }", 1),
        Case("actual primary constructor", "actual class Host actual constructor(value: Int)", 0),
        Case("ordinary constructor", "actual class Host constructor(value: Int)", 1),
        Case("actual constructor property", "actual class Host actual constructor(actual val value: Int)", 0),
        Case("extra constructor property", "actual class Host actual constructor(val value: Int)", 1),
        Case("internal constructor property", "actual class Host actual constructor(internal val value: Int)", 0),
        Case("private constructor", "actual class Host private constructor(value: Int)", 0),
        Case("private constructor public property", "actual class Host private constructor(val value: Int)", 1),
        Case("actual secondary constructor", "actual class Host { actual constructor(value: Int) }", 0),
        Case("extra secondary constructor", "actual class Host { constructor(value: Int) }", 1),
        Case("function locals", "actual fun run() { class Local; fun local() {} }", 0),
        Case("property expression", "actual val service = object : Runnable { override fun run() {} }", 0),
        Case("initializer implementation", "actual class Host { init { use(object : Port { override fun run() {} }) } }", 0),
        Case("object delegation", "actual class Host : Port by object : Port { override fun run() {} }", 0),
        Case("initializer locals", "actual class Host { init { class Local; fun local() {} } }", 0),
        Case("actual property getter body", "actual val value: Int get() { class Local; return 1 }", 0),
        Case("actual enum structure", "actual enum class Mode { One, Two; actual fun run() {} }", 0),
        Case("actual enum extra method", "actual enum class Mode { One; fun extra() {} }", 1),
        Case("protected export", "actual open class Host { protected fun extra() {} }", 1),
        Case("two top-level violations", "fun one() {}; fun two() {}", 2),
    ).map { case ->
        dynamicTest(case.name) {
            assertEquals(case.findings, PublicApiOutsideSpec(Config.empty).lint(case.code).size, case.code)
        }
    }

    private data class Case(val name: String, val code: String, val findings: Int)
}
