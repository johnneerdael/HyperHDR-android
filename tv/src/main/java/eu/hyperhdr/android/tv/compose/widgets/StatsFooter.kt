package eu.hyperhdr.android.tv.compose.widgets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import eu.hyperhdr.android.stats.LiveStats
import eu.hyperhdr.android.tv.update.UpdateInstaller
import java.util.Locale

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun StatsFooter(
    stats: LiveStats,
    hdrBadge: Boolean,
    updateAvailable: AvailableUpgrade? = null,
    updateState: UpdateInstaller.State = UpdateInstaller.State.Idle,
    onUpgradeClick: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(top = 24.dp).testTag("stats_footer"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val text = buildString {
            if (stats.width != 0) {
                append(String.format(
                    Locale.US,
                    "%.1f fps · %d KB/s · %dx%d",
                    stats.fps, stats.bytesPerSec / 1024, stats.width, stats.height,
                ))
                stats.lastErrorMessage?.let { append("  ⚠  ").append(it) }
                if (hdrBadge) append("  ✦ HDR")
            }
        }
        if (text.isNotEmpty()) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (updateAvailable != null) {
            val label = when (updateState) {
                is UpdateInstaller.State.Downloading -> "Downloading v${updateAvailable.tag}… ${updateState.percent}%"
                UpdateInstaller.State.Dispatched -> "Installer launched"
                UpdateInstaller.State.NeedsPermission -> "Allow installs in system settings, then retry"
                is UpdateInstaller.State.Failed -> "Update failed: ${updateState.message} — retry"
                UpdateInstaller.State.Idle -> "⬆  v${updateAvailable.tag} available — install"
            }
            val isBusy = updateState is UpdateInstaller.State.Downloading
            FocusableSurface(
                onClick = { if (!isBusy) onUpgradeClick() },
                enabled = !isBusy,
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                modifier = Modifier.testTag("upgrade_banner"),
            ) {
                Text(text = label, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** Tag + downloadUrl pair surfaced from the GitHub releases endpoint. */
data class AvailableUpgrade(val tag: String, val downloadUrl: String?)
