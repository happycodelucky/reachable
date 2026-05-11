/*
 * Reachable — Android sample app.
 *
 * One screen, one observable: subscribes to `Reachability.shared.status`
 * via `collectAsStateWithLifecycle()` and renders the live
 * ReachabilityStatus. Toggle airplane mode or switch between Wi-Fi and
 * cellular to see transitions arrive in real time.
 *
 * No construction, no Context plumbing: `Reachability.shared` is the
 * process-lifetime singleton, attached to the application Context by the
 * library's bundled `androidx.startup` initializer before
 * `Application.onCreate`. Calling `close()` on it would be a no-op (see
 * `Reachability.shared`'s KDoc); the OS reaps the platform observer at
 * process exit.
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
    // No `remember` needed — `Reachability.shared` is the process-lifetime
    // singleton, attached to the application Context by the library's
    // bundled `androidx.startup` initializer before `Application.onCreate`.
    val status by Reachability.shared.status.collectAsStateWithLifecycle()

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
