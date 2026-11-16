package io.github.roberbarras.racha.desktop

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SingleInstanceTest {
    private val dir = Files.createTempDirectory("racha-instance")
    private val path = dir.resolve("racha.sock")

    @Test
    fun aSecondLaunchHandsItsLinkToTheFirst() = runBlocking<Unit> {
        val first = assertNotNull(SingleInstance.claim("", path))
        first.use {
            assertNull(SingleInstance.claim("racha://item/${"a".repeat(64)}", path), "the second launch exits")
            assertEquals("racha://item/${"a".repeat(64)}", withTimeout(5_000) { first.received.first() })
        }
        assertNotNull(SingleInstance.claim("", path), "after closing, the next launch runs").close()
    }

    @Test
    fun aSocketLeftByACrashIsReplaced() {
        Files.writeString(path, "stale")
        assertNotNull(SingleInstance.claim("", path)).close()
    }
}
