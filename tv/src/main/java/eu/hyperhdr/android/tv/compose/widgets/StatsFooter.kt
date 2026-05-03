package eu.hyperhdr.android.tv.compose.widgets

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import eu.hyperhdr.android.stats.LiveStats
import java.util.Locale

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun StatsFooter(
    stats: LiveStats,
    hdrBadge: Boolean,
    updateAvailable: String? = null,
    modifier: Modifier = Modifier,
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
        if (updateAvailable != null) {
            if (isNotEmpty()) append("  ")
            append("⬆  v").append(updateAvailable).append(" available")
        }
    }
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(top = 24.dp).testTag("stats_footer"),
    )
}
