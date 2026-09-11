package io.github.youndie.kore

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KorePlatformLinuxTest {
    @Test
    fun `the linux targets can enumerate the environment`() {
        assertEquals("linux", korePlatform().name)
        assertTrue(korePlatform().canEnumerateEnvironment)
    }
}
