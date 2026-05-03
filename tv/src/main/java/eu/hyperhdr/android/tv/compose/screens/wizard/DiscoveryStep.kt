package eu.hyperhdr.android.tv.compose.screens.wizard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.foundation.lazy.list.TvLazyColumn
import androidx.tv.foundation.lazy.list.items
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import eu.hyperhdr.android.discovery.DiscoveredServer
import eu.hyperhdr.android.discovery.HyperHdrMdnsDiscovery
import eu.hyperhdr.android.tv.compose.widgets.ClickablePreference

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun DiscoveryStep(
    discovery: HyperHdrMdnsDiscovery,
    onPick: (DiscoveredServer) -> Unit,
    onManual: () -> Unit,
) {
    val servers by discovery.servers.collectAsStateWithLifecycle(emptyList())

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(48.dp),
    ) {
        Text("Looking for HyperHDR servers…", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Pick a server below or enter manually.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))

        TvLazyColumn {
            items(servers) { server ->
                ClickablePreference(
                    title = server.name,
                    summary = "${server.host}:${server.flatbufPort}",
                    onClick = { onPick(server) },
                )
            }
            item {
                ClickablePreference(title = "Enter manually…", onClick = onManual)
            }
        }
    }
}
