package eu.hyperhdr.android.tv.compose.screens.wizard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
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
import eu.hyperhdr.android.settings.ServerProfile

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun ManualStep(onNext: (ServerProfile) -> Unit) {
    var host by remember { mutableStateOf("") }
    var flatPort by remember { mutableStateOf("19400") }
    var jsonPort by remember { mutableStateOf("19444") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(48.dp),
    ) {
        Text("Enter HyperHDR server", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(24.dp))

        OutlinedTextField(
            value = host,
            onValueChange = { host = it },
            label = { androidx.compose.material3.Text("Host (e.g. 192.168.1.10)") },
            modifier = Modifier.padding(vertical = 4.dp),
        )
        OutlinedTextField(
            value = flatPort,
            onValueChange = { flatPort = it },
            label = { androidx.compose.material3.Text("Flatbuffer port") },
            modifier = Modifier.padding(vertical = 4.dp),
        )
        OutlinedTextField(
            value = jsonPort,
            onValueChange = { jsonPort = it },
            label = { androidx.compose.material3.Text("JSON-API port") },
            modifier = Modifier.padding(vertical = 4.dp),
        )

        Spacer(Modifier.height(16.dp))
        Button(
            onClick = {
                if (host.isNotBlank()) {
                    onNext(ServerProfile(
                        host = host.trim(),
                        flatbufPort = flatPort.toIntOrNull() ?: 19400,
                        jsonPort = jsonPort.toIntOrNull() ?: 19444,
                    ))
                }
            },
        ) {
            Text("Continue", modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        }
    }
}
