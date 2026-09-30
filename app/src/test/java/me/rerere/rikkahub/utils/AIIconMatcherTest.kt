package me.rerere.rikkahub.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class AIIconMatcherTest {
    @Test
    fun `codex provider uses the dedicated codex icon`() {
        assertEquals("codex.svg", computeAIIconByName("Codex"))
    }
}
