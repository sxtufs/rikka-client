package me.rerere.rikkahub.data.codex

import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.ModelAbility
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CodexProviderTest {
    @Test
    fun `codex picker keeps visible subscription models and their capabilities`() {
        val models = parseCodexModels(
            """{"models":[
                {"slug":"gpt-5.6-luna","display_name":"GPT-5.6 Luna","visibility":"list","input_modalities":["text","image"],"supported_reasoning_levels":["low"],"context_window":200000},
                {"slug":"gpt-5.6-luna","display_name":"duplicate","visibility":"hidden"}
            ]}"""
        )

        assertEquals(1, models.size)
        assertEquals("gpt-5.6-luna", models.single().modelId)
        assertEquals(listOf(Modality.TEXT, Modality.IMAGE), models.single().inputModalities)
        assertTrue(ModelAbility.REASONING in models.single().abilities)
        assertEquals(200000, models.single().contextLength)
    }
}
