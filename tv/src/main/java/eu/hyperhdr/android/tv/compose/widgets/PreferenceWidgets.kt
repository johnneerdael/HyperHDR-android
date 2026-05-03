package eu.hyperhdr.android.tv.compose.widgets

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Card
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Switch
import androidx.tv.material3.Text

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun CategoryHeader(title: String, modifier: Modifier = Modifier) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(top = 24.dp, bottom = 8.dp),
    )
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SwitchPreference(
    title: String,
    summary: String? = null,
    checked: Boolean,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var localChecked by remember(checked) { mutableStateOf(checked) }
    Card(
        onClick = {
            if (enabled) {
                val next = !localChecked
                localChecked = next
                onChange(next)
            }
        },
        modifier = modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                if (summary != null) {
                    Text(
                        summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.width(16.dp))
            Switch(checked = localChecked, onCheckedChange = null, enabled = enabled)
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun ClickablePreference(
    title: String,
    summary: String? = null,
    enabled: Boolean = true,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        onClick = { if (enabled) onClick() },
        modifier = modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (summary != null) {
                Text(
                    summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Editable text preference. Compose-for-TV's idiom for text input is to delegate to a
 * full-screen editor on click rather than inline editing (which behaves badly with d-pad).
 * For v1.0.0 we keep it simple — clicking shows current value; the underlying preference is
 * edited via a dialog activity that's already part of the wizard flow.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun EditTextPreference(
    title: String,
    value: String,
    summary: String = value,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ClickablePreference(title = title, summary = summary, onClick = onClick, modifier = modifier)
}
