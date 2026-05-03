package eu.hyperhdr.android.tv.compose.screens.wizard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.tv.foundation.lazy.list.TvLazyColumn
import androidx.tv.foundation.lazy.list.items
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import eu.hyperhdr.android.json.HyperHdrJsonApiClient
import eu.hyperhdr.android.json.Instance
import eu.hyperhdr.android.settings.ServerProfile
import eu.hyperhdr.android.tv.compose.widgets.ClickablePreference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun InstanceStep(draft: ServerProfile, onPick: (ServerProfile) -> Unit) {
    var instances by remember { mutableStateOf<List<Instance>?>(null) }

    LaunchedEffect(draft.host, draft.jsonPort, draft.token) {
        val client = HyperHdrJsonApiClient(draft.host, draft.jsonPort)
        draft.token?.let { runCatching { client.authorize(it) } }
        instances = withContext(Dispatchers.IO) {
            runCatching { client.serverInfo().instances }.getOrNull()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(48.dp),
    ) {
        Text("Pick the instance", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Which HyperHDR instance should this device drive?",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))

        when (val list = instances) {
            null -> Text("Loading…", style = MaterialTheme.typography.bodyMedium)
            else -> if (list.isEmpty()) {
                Text(
                    "No instances reported — check the server.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            } else {
                TvLazyColumn {
                    items(list) { inst ->
                        ClickablePreference(
                            title = inst.name,
                            summary = if (inst.running) "Running (id=${inst.id})" else "Stopped (id=${inst.id})",
                            onClick = { onPick(draft.copy(instanceId = inst.id)) },
                        )
                    }
                }
            }
        }
    }
}
