package me.rerere.rikkahub.ui.pages.setting.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.codex.CodexAccount
import me.rerere.rikkahub.data.codex.CodexAccountRepository
import me.rerere.rikkahub.data.codex.CodexOAuthManager
import me.rerere.rikkahub.data.codex.CodexOAuthStatus
import me.rerere.rikkahub.data.codex.CodexTokenStatus
import me.rerere.rikkahub.data.gemini.GeminiAccount
import me.rerere.rikkahub.data.gemini.GeminiAccountRepository
import me.rerere.rikkahub.data.gemini.GeminiOAuthManager
import me.rerere.rikkahub.data.gemini.GeminiOAuthStatus
import me.rerere.rikkahub.data.gemini.GeminiTokenStatus
import me.rerere.rikkahub.data.grok.GrokAccount
import me.rerere.rikkahub.data.grok.GrokAccountRepository
import me.rerere.rikkahub.data.grok.GrokOAuthManager
import me.rerere.rikkahub.data.grok.GrokOAuthStatus
import me.rerere.rikkahub.data.grok.GrokTokenStatus
import org.koin.compose.koinInject
import java.text.DateFormat
import java.util.Date

@Composable
internal fun OAuthProviderConfigureScreen(
    provider: ProviderSetting,
    onEdit: (ProviderSetting) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Text(provider.name, style = MaterialTheme.typography.headlineSmall)

        when (provider) {
            is ProviderSetting.Codex -> CodexOAuthConfigure(provider, onEdit)
            is ProviderSetting.Grok -> GrokOAuthConfigure(provider, onEdit)
            is ProviderSetting.GeminiOAuth -> GeminiOAuthConfigure(provider, onEdit)
            else -> Unit
        }
    }
}

@Composable
private fun CodexOAuthConfigure(
    provider: ProviderSetting.Codex,
    onEdit: (ProviderSetting) -> Unit,
) {
    val manager: CodexOAuthManager = koinInject()
    val repository: CodexAccountRepository = koinInject()
    val accounts by repository.accounts.collectAsStateWithLifecycle()
    val status by manager.status.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val canEnable = accounts.any { it.enabled && it.tokenStatus != CodexTokenStatus.INVALID }

    Text(
        "Uses the OpenAI Codex OAuth and Responses API flow. This integration depends on Codex service compatibility.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Button(onClick = manager::startLogin, modifier = Modifier.fillMaxWidth()) {
        Text("Sign in with OpenAI OAuth")
    }
    OAuthEnableRow(
        label = "Enable Codex",
        subtitle = "Use enabled accounts in round-robin order.",
        checked = provider.enabled,
        enabled = canEnable || provider.enabled,
        onCheckedChange = { onEdit(provider.copyProvider(enabled = it)) },
    )
    OAuthStatusText(status)
    AccountsHeader(
        count = accounts.size,
        onRefresh = { scope.launch { repository.refreshAll() } },
        enabled = accounts.isNotEmpty(),
    )
    if (accounts.isEmpty()) {
        EmptyAccountsText("No OpenAI accounts are signed in.")
    } else {
        accounts.forEach { account ->
            CodexAccountCard(
                account = account,
                onEnabledChange = { enabled ->
                    scope.launch { repository.setEnabled(account.id, enabled) }
                },
                onRefresh = { scope.launch { repository.refreshAccount(account.id) } },
                onReauth = manager::startLogin,
                onDelete = { scope.launch { repository.delete(account.id) } },
            )
        }
    }
}

@Composable
private fun GrokOAuthConfigure(
    provider: ProviderSetting.Grok,
    onEdit: (ProviderSetting) -> Unit,
) {
    val manager: GrokOAuthManager = koinInject()
    val repository: GrokAccountRepository = koinInject()
    val accounts by repository.accounts.collectAsStateWithLifecycle()
    val status by manager.status.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val canEnable = accounts.any { it.enabled && it.tokenStatus != GrokTokenStatus.INVALID }

    Text(
        "Uses your xAI Grok subscription (SuperGrok or X Premium+) via OAuth device sign-in and the xAI Responses API. Requires an active subscription; no API key needed.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Button(onClick = manager::startLogin, modifier = Modifier.fillMaxWidth()) {
        Text("Sign in with xAI")
    }
    OAuthEnableRow(
        label = "Enable Grok",
        subtitle = "Sign in with at least one available account first.",
        checked = provider.enabled,
        enabled = canEnable || provider.enabled,
        onCheckedChange = { onEdit(provider.copyProvider(enabled = it)) },
    )
    GrokOAuthStatusText(status)
    AccountsHeader(
        count = accounts.size,
        onRefresh = { scope.launch { repository.refreshAll() } },
        enabled = accounts.isNotEmpty(),
    )
    if (accounts.isEmpty()) {
        EmptyAccountsText("No Grok accounts are signed in.")
    } else {
        accounts.forEach { account ->
            GrokAccountCard(
                account = account,
                onEnabledChange = { enabled ->
                    scope.launch { repository.setEnabled(account.id, enabled) }
                },
                onRefresh = { scope.launch { repository.refreshAccount(account.id) } },
                onReauth = manager::startLogin,
                onDelete = { scope.launch { repository.delete(account.id) } },
            )
        }
    }
}

@Composable
private fun GeminiOAuthConfigure(
    provider: ProviderSetting.GeminiOAuth,
    onEdit: (ProviderSetting) -> Unit,
) {
    val manager: GeminiOAuthManager = koinInject()
    val repository: GeminiAccountRepository = koinInject()
    val accounts by repository.accounts.collectAsStateWithLifecycle()
    val status by manager.status.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val canEnable = accounts.any { it.enabled && it.tokenStatus != GeminiTokenStatus.INVALID }

    Text(
        "Signs in with a Google account and generates through Google Cloud Code Assist, the same backend Antigravity uses. No API key needed.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("This may get your Google account banned", style = MaterialTheme.typography.titleMedium)
            Text(
                "This provider signs in with Antigravity's own OAuth credentials and calls a private Google endpoint from an app Google did not publish. Google's terms let them suspend or terminate accounts for accessing their services through unapproved clients, and there is no appeal path built for this. Use a throwaway Google account you can afford to lose, never one tied to your email, photos, purchases, or work.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
    Button(onClick = manager::startLogin, modifier = Modifier.fillMaxWidth()) {
        Text("Sign in with Google")
    }
    OAuthEnableRow(
        label = "Enable Gemini OAuth",
        subtitle = "Sign in with at least one Google account first.",
        checked = provider.enabled,
        enabled = canEnable || provider.enabled,
        onCheckedChange = { onEdit(provider.copyProvider(enabled = it)) },
    )
    GeminiOAuthStatusText(status)
    AccountsHeader(
        count = accounts.size,
        onRefresh = { scope.launch { repository.refreshAll() } },
        enabled = accounts.isNotEmpty(),
    )
    if (accounts.isEmpty()) {
        EmptyAccountsText("No Google accounts are signed in.")
    } else {
        accounts.forEach { account ->
            GeminiAccountCard(
                account = account,
                onEnabledChange = { enabled ->
                    scope.launch { repository.setEnabled(account.id, enabled) }
                },
                onRefresh = { scope.launch { repository.refreshAccount(account.id) } },
                onReauth = manager::startLogin,
                onDelete = { scope.launch { repository.delete(account.id) } },
            )
        }
    }
}

@Composable
private fun OAuthEnableRow(
    label: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(
            checked = checked,
            enabled = enabled,
            onCheckedChange = onCheckedChange,
        )
    }
}

@Composable
private fun AccountsHeader(
    count: Int,
    onRefresh: () -> Unit,
    enabled: Boolean,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Accounts ($count)", style = MaterialTheme.typography.titleMedium)
        OutlinedButton(onClick = onRefresh, enabled = enabled) {
            Text("Check status")
        }
    }
}

@Composable
private fun EmptyAccountsText(text: String) {
    Text(
        text,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 48.dp),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun CodexAccountCard(
    account: CodexAccount,
    onEnabledChange: (Boolean) -> Unit,
    onRefresh: () -> Unit,
    onReauth: () -> Unit,
    onDelete: () -> Unit,
) {
    OAuthAccountCard(
        title = account.name.ifBlank { "OpenAI account" },
        subtitle = account.email,
        enabled = account.enabled,
        onEnabledChange = onEnabledChange,
        status = when (account.tokenStatus) {
            CodexTokenStatus.INVALID -> "Token invalid"
            CodexTokenStatus.EXPIRED -> "Token expired"
            else -> "Token available"
        },
        onRefresh = onRefresh,
        onReauth = onReauth,
        onDelete = onDelete,
    ) {
        account.usage?.primary?.let {
            UsageWindow("5-hour limit", it.usedPercent, it.resetsAt)
        }
        account.usage?.secondary?.let {
            UsageWindow("Weekly limit", it.usedPercent, it.resetsAt)
        }
    }
}

@Composable
private fun GrokAccountCard(
    account: GrokAccount,
    onEnabledChange: (Boolean) -> Unit,
    onRefresh: () -> Unit,
    onReauth: () -> Unit,
    onDelete: () -> Unit,
) {
    OAuthAccountCard(
        title = account.name.ifBlank { "Grok account" },
        subtitle = account.email,
        enabled = account.enabled,
        onEnabledChange = onEnabledChange,
        status = when (account.tokenStatus) {
            GrokTokenStatus.INVALID -> "Token invalid"
            GrokTokenStatus.EXPIRED -> "Token expired"
            else -> account.usage?.planName ?: "Token available"
        },
        onRefresh = onRefresh,
        onReauth = onReauth,
        onDelete = onDelete,
    ) {
        account.usage?.planName?.let { plan ->
            Text("Plan: $plan")
        }
        account.usage?.weekly?.let {
            UsageWindow("Weekly limit", it.usedPercent, it.resetsAt)
        }
        account.usage?.let {
            Text("Pay as you go: ${if (it.onDemandCap > 0.0) "enabled" else "disabled"}")
        }
    }
}

@Composable
private fun GeminiAccountCard(
    account: GeminiAccount,
    onEnabledChange: (Boolean) -> Unit,
    onRefresh: () -> Unit,
    onReauth: () -> Unit,
    onDelete: () -> Unit,
) {
    OAuthAccountCard(
        title = account.name.ifBlank { "Google account" },
        subtitle = account.email,
        enabled = account.enabled,
        onEnabledChange = onEnabledChange,
        status = when (account.tokenStatus) {
            GeminiTokenStatus.INVALID -> "Token invalid"
            GeminiTokenStatus.EXPIRED -> "Token expired"
            else -> "Token available"
        },
        onRefresh = onRefresh,
        onReauth = onReauth,
        onDelete = onDelete,
    ) { }
}

@Composable
private fun OAuthAccountCard(
    title: String,
    subtitle: String,
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    status: String,
    onRefresh: () -> Unit,
    onReauth: () -> Unit,
    onDelete: () -> Unit,
    usage: @Composable ColumnScope.() -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    if (subtitle.isNotBlank()) {
                        Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Switch(checked = enabled, onCheckedChange = onEnabledChange)
            }
            Text(status, color = MaterialTheme.colorScheme.primary)
            Column(verticalArrangement = Arrangement.spacedBy(12.dp), content = usage)
            HorizontalDivider()
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onRefresh) { Text("Refresh") }
                TextButton(onClick = onReauth) { Text("Re-auth") }
                TextButton(onClick = onDelete) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun UsageWindow(label: String, usedPercent: Double, resetsAt: Long?) {
    val remaining = (100.0 - usedPercent).coerceIn(0.0, 100.0)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label)
            Text("${remaining.toInt()}% remaining")
        }
        LinearProgressIndicator(
            progress = { (remaining / 100.0).toFloat() },
            modifier = Modifier.fillMaxWidth(),
        )
        resetsAt?.let {
            Text(
                "Resets ${DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it * 1000))}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun OAuthStatusText(status: CodexOAuthStatus) {
    when (status) {
        CodexOAuthStatus.Idle -> Unit
        CodexOAuthStatus.Waiting -> Text("Waiting for OpenAI authorization…")
        is CodexOAuthStatus.Success -> Unit
        is CodexOAuthStatus.Error -> Text("OpenAI sign-in failed: ${status.message}", color = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun GrokOAuthStatusText(status: GrokOAuthStatus) {
    when (status) {
        GrokOAuthStatus.Idle -> Unit
        GrokOAuthStatus.Starting -> Text("Starting xAI sign-in…")
        is GrokOAuthStatus.AwaitingApproval -> Text("Approve xAI sign-in in your browser")
        is GrokOAuthStatus.Success -> Unit
        is GrokOAuthStatus.Error -> Text("xAI sign-in failed: ${status.message}", color = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun GeminiOAuthStatusText(status: GeminiOAuthStatus) {
    when (status) {
        GeminiOAuthStatus.Idle -> Unit
        GeminiOAuthStatus.Waiting -> Text("Waiting for Google authorization…")
        is GeminiOAuthStatus.Success -> Unit
        is GeminiOAuthStatus.Error -> Text("Google sign-in failed: ${status.message}", color = MaterialTheme.colorScheme.error)
    }
}
