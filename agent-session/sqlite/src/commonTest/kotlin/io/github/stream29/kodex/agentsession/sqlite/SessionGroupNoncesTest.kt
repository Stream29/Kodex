@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.stream29.kodex.agentsession.sqlite

import de.infix.testBalloon.framework.core.testSuite
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.assertEquals

val sessionGroupNoncesTest by testSuite {
    test("only affected group watchers change; removing a pre-existing untouched group is still observable") {
        runTest {
            val notifier = SessionGroupNonces()
            val groups = mutableListOf<Long>()
            val unset = mutableListOf<Long>()
            val folder = mutableListOf<Long>()
            val listWatch = launch { notifier.groups.collect { groups += it } }
            val unsetWatch = launch { notifier.group(null).collect { unset += it } }
            val folderWatch = launch { notifier.group("/a").collect { folder += it } }
            runCurrent()
            notifier.committed(mapOf("/a" to true), emptyMap())
            runCurrent()
            assertEquals(listOf(0L, 1L), groups)
            assertEquals(listOf(0L, 1L), folder)
            assertEquals(listOf(0L), unset)
            notifier.committed(mapOf(null to false), emptyMap())
            runCurrent()
            assertEquals(listOf(0L, 1L, 2L), groups)
            assertEquals(listOf(0L, 2L), unset)
            assertEquals(2L, notifier.group(null).first())
            unsetWatch.cancelAndJoin()
            assertEquals(0L, notifier.group(null).first())
            folderWatch.cancelAndJoin()
            listWatch.cancelAndJoin()
        }
    }

    test("running rows always update; a second running root in the same group leaves the heads alone") {
        runTest {
            val notifier = SessionGroupNonces()
            val groups = mutableListOf<Long>()
            val page = mutableListOf<Long>()
            val listWatch = launch { notifier.groups.collect { groups += it } }
            val pageWatch = launch { notifier.group(null).collect { page += it } }
            runCurrent()
            notifier.runningChanged(mapOf(1 to null))
            runCurrent()
            notifier.runningChanged(mapOf(1 to null, 2 to null))
            runCurrent()
            notifier.runningChanged(mapOf(2 to null))
            runCurrent()
            notifier.runningChanged(emptyMap())
            runCurrent()
            assertEquals(listOf(0L, 1L, 4L), groups)
            assertEquals(listOf(0L, 1L, 2L, 3L, 4L), page)
            pageWatch.cancelAndJoin()
            listWatch.cancelAndJoin()
        }
    }
}
