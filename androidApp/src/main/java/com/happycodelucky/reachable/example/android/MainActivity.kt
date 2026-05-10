/*
 * Reachable — Android sample app.
 *
 * One screen, one observable: subscribes to `reachability.status` via
 * `collectAsStateWithLifecycle()` and renders the live ReachabilityStatus.
 * Toggle airplane mode or switch between Wi-Fi and cellular to see
 * transitions arrive in real time.
 *
 * The Reachability instance is built once with `remember(context)` against
 * the application context (avoids leaking the activity) and never explicitly
 * closed — the OS reaps the process on app exit. A real production app
 * would hoist construction into `Application.onCreate()` and call
 * `reachability.close()` in `onTerminate()`.
 */
package com.happycodelucky.reachable.example.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.happycodelucky.reachable.Reachability
import com.happycodelucky.reachable.ReachabilityStatus

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    ReachabilityScreen()
                }
            }
        }
    }
}

@Composable
private fun ReachabilityScreen() {
    val context = LocalContext.current
    // Construct once per recomposition lifetime; rebuilt only if the Context
    // identity changes (it won't, in practice).
    val reachability = remember(context) { Reachability(context) }
    val status by reachability.status.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = if (status.reachable) "Online" else "Offline",
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Bold,
        )
        StatusRow("Transport", status.transport.name)
        StatusRow("Metering", status.metering.name)
        Text(
            text = "Toggle airplane mode or switch networks to see live updates.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "(raw) " + raw(status),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
private fun StatusRow(
    label: String,
    value: String,
) {
    Text(
        text = "$label: $value",
        style = MaterialTheme.typography.titleMedium,
    )
}

private fun raw(status: ReachabilityStatus): String =
    "reachable=${status.reachable} " +
        "transport=${status.transport} " +
        "metering=${status.metering}"
