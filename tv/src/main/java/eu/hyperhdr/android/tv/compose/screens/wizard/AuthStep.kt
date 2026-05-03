package eu.hyperhdr.android.tv.compose.screens.wizard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import eu.hyperhdr.android.json.HyperHdrJsonApiClient
import eu.hyperhdr.android.settings.ServerProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun AuthStep(draft: ServerProfile, onNext: (ServerProfile) -> Unit) {
    var token by remember { mutableStateOf("") }
    var authRequired by remember { mutableStateOf<Boolean?>(null) }

    LaunchedEffect(draft.host, draft.jsonPort) {
        val client = HyperHdrJsonApiClient(draft.host, draft.jsonPort)
        authRequired = withContext(Dispatchers.IO) {
            runCatching { client.serverInfo().authRequired }.getOrElse { false }
        }
        if (authRequired == false) onNext(draft)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(48.dp),
    ) {
        Text("Authentication", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            when (authRequired) {
                null -> "Probing the server…"
                true -> "Server requires a token. Open HyperHDR's web UI → Network Services → API Tokens to create one."
                false -> "Server is open — proceeding…"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))

        if (authRequired == true) {
            OutlinedTextField(
                value = token,
                onValueChange = { token = it },
                label = { androidx.compose.material3.Text("Token") },
            )
            Spacer(Modifier.height(16.dp))
            Row {
                Button(onClick = {
                    onNext(draft.copy(token = token.takeIf { it.isNotBlank() }))
                }) {
                    Text("Continue", modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                }
                Spacer(Modifier.width(16.dp))
                Button(onClick = { onNext(draft) }) {
                    Text("Skip (open server)", modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                }
            }
        }
    }
}
