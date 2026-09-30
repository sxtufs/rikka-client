package me.rerere.rikkahub.ui.pages.setting.components

import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.ui.components.ui.TagType

/** Optional precondition badges shown on provider cards. */
data class ProviderRequirement(
    val label: String,
    val severity: TagType,
) {
    companion object {
        // The current fork does not include the reference project's on-device provider types.
        // OAuth-specific warnings are rendered on their configuration screens instead.
        fun from(provider: ProviderSetting): List<ProviderRequirement> = emptyList()
    }
}
