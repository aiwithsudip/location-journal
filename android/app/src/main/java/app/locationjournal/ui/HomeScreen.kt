package app.locationjournal.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import app.locationjournal.domain.TrackingState
import app.locationjournal.domain.formatDistance
import app.locationjournal.domain.formatDuration
import java.time.LocalTime

@Composable
fun HomeScreen(viewModel: JournalViewModel, nav: NavController) {
    val settings by viewModel.settings.collectAsState()
    val state by viewModel.trackingState.collectAsState()
    val summary by viewModel.summary.collectAsState()
    val day by viewModel.selectedDay.collectAsState()
    val torch by viewModel.torchState.collectAsState()
    val scroll = rememberScrollState()
    val requestLocation = rememberLocationPermissionRequest { granted -> if (granted) viewModel.startTracking() }
    val requestBackground = rememberBackgroundPermissionRequest { }
    val locationOk = hasLocationPermission()
    val backgroundOk = hasBackgroundPermission()
    val night = isNight(LocalTime.now().hour, settings.nightStartHour, settings.nightEndHour)

    Column(
        Modifier.fillMaxSize().verticalScroll(scroll).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text("Today", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text(day, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            OutlinedButton(onClick = { viewModel.selectDay(shiftDay(day, -1)) }) { Text("Prev") }
            OutlinedButton(onClick = { viewModel.selectDay(shiftDay(day, 1)) }) { Text("Next") }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    when (state) {
                        TrackingState.RUNNING -> "Tracking is active"
                        TrackingState.PAUSED -> "Tracking is paused"
                        TrackingState.STOPPED -> "Tracking is stopped"
                    },
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    if (state == TrackingState.RUNNING) "A notification stays on screen. New points are saved on this device."
                    else "Start tracking to record a route. Nothing is uploaded.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        modifier = Modifier.weight(1f),
                        onClick = {
                            if (!locationOk) requestLocation()
                            else when (state) {
                                TrackingState.STOPPED -> viewModel.startTracking()
                                TrackingState.RUNNING -> viewModel.pauseTracking()
                                TrackingState.PAUSED -> viewModel.resumeTracking()
                            }
                        },
                    ) {
                        Text(when (state) {
                            TrackingState.RUNNING -> "Pause"
                            TrackingState.PAUSED -> "Resume"
                            TrackingState.STOPPED -> "Start tracking"
                        })
                    }
                    if (state != TrackingState.STOPPED) {
                        OutlinedButton(onClick = viewModel::stopTracking, modifier = Modifier.weight(1f)) { Text("Stop") }
                    }
                }
                OutlinedButton(onClick = viewModel::saveFix, modifier = Modifier.fillMaxWidth()) { Text("Save a fix now") }
                if (!backgroundOk) {
                    OutlinedButton(onClick = requestBackground, modifier = Modifier.fillMaxWidth()) {
                        Text("Enable background location (optional)")
                    }
                }
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatCard("Distance", formatDistance(summary.distanceMeters, settings.units), Modifier.weight(1f))
            StatCard("Time tracked", formatDuration(summary.trackedMs), Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatCard("Visits", summary.visitCount.toString(), Modifier.weight(1f))
            StatCard("Points", summary.sampleCount.toString(), Modifier.weight(1f))
        }

        if (night) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Flashlight", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text("Available from 6 PM to 6 AM. It turns itself off when daytime returns.", style = MaterialTheme.typography.bodyMedium)
                    Button(
                        onClick = viewModel::toggleTorch,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = torch.available,
                    ) { Text(if (torch.on) "Flashlight on" else "Turn on flashlight") }
                }
            }
        }

        SectionTitle("Quick actions")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = { nav.navigate("map") }, modifier = Modifier.weight(1f)) { Text("Map") }
            FilledTonalButton(onClick = { nav.navigate("timeline") }, modifier = Modifier.weight(1f)) { Text("History") }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = { nav.navigate("photos") }, modifier = Modifier.weight(1f)) { Text("Photos") }
            FilledTonalButton(onClick = { nav.navigate("journal") }, modifier = Modifier.weight(1f)) { Text("Notes") }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = { nav.navigate("insights") }, modifier = Modifier.weight(1f)) { Text("Insights") }
            FilledTonalButton(onClick = { nav.navigate("settings") }, modifier = Modifier.weight(1f)) { Text("Settings") }
        }
    }
}

private fun isNight(hour: Int, start: Int, end: Int): Boolean =
    if (start > end) hour >= start || hour < end else hour in start until end

private fun shiftDay(day: String, delta: Int): String {
    val date = java.time.LocalDate.parse(day)
    return date.plusDays(delta.toLong()).toString()
}
