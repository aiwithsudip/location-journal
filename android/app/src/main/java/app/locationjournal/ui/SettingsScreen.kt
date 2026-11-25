package app.locationjournal.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import app.locationjournal.domain.DistanceUnit
import app.locationjournal.domain.ThemeMode
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val EXPORT_STAMP_FORMAT = DateTimeFormatter.ofPattern("MMM d, yyyy · HH:mm", Locale.US)
private const val HOUR_MS = 60L * 60 * 1000
private const val DAY_MS = 24 * HOUR_MS

@Composable
fun SettingsScreen(viewModel: JournalViewModel, nav: NavController) {
    val settings by viewModel.settings.collectAsState()
    val day by viewModel.selectedDay.collectAsState()
    val stamp = LocalDate.now().toString()

    var pendingImport by remember { mutableStateOf<Uri?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    var confirmDeleteDay by remember { mutableStateOf(false) }

    val createJson = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) viewModel.exportJson(uri)
    }
    val createGpx = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/gpx+xml")) { uri ->
        if (uri != null) viewModel.exportGpx(uri)
    }
    val createCsv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) viewModel.exportCsv(uri)
    }
    val openJson = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) pendingImport = uri
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Private by design", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    "This journal works offline. GPS points, visits, photos, notes, and settings stay in this app's storage on this device. " +
                        "There is no account, no analytics, and no upload. Exports are files you choose to save yourself.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        SectionTitle("Display")
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Distance units", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                ChoiceGroup(
                    options = listOf(DistanceUnit.KM to "Kilometres", DistanceUnit.MI to "Miles"),
                    selected = settings.units,
                    onSelect = { value -> viewModel.updateSettings { it.copy(units = value) } },
                )
                Text("Theme", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                ChoiceGroup(
                    options = listOf(ThemeMode.SYSTEM to "System", ThemeMode.LIGHT to "Light", ThemeMode.DARK to "Dark"),
                    selected = settings.theme,
                    onSelect = { value -> viewModel.updateSettings { it.copy(theme = value) } },
                )
            }
        }

        SectionTitle("Tracking")
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberStepper(
                    label = "Sample interval",
                    value = settings.sampleIntervalSeconds.toInt(),
                    min = 5,
                    max = 300,
                    step = 5,
                    suffix = " s",
                    onChange = { value -> viewModel.updateSettings { it.copy(sampleIntervalSeconds = value.toLong()) } },
                )
                NumberStepper(
                    label = "Stay radius",
                    value = settings.stayRadiusMeters,
                    min = 50,
                    max = 1000,
                    step = 50,
                    suffix = " m",
                    onChange = { value -> viewModel.updateSettings { it.copy(stayRadiusMeters = value) } },
                )
                NumberStepper(
                    label = "Minimum stay duration",
                    value = settings.stayMinMinutes,
                    min = 5,
                    max = 180,
                    step = 5,
                    suffix = " min",
                    onChange = { value -> viewModel.updateSettings { it.copy(stayMinMinutes = value) } },
                )
                NumberStepper(
                    label = "Maximum gap inside a stay",
                    value = settings.stayMaxGapMinutes,
                    min = 10,
                    max = 240,
                    step = 10,
                    suffix = " min",
                    onChange = { value -> viewModel.updateSettings { it.copy(stayMaxGapMinutes = value) } },
                )
                NumberStepper(
                    label = "Maximum accepted GPS error",
                    value = settings.maxAccuracyMeters,
                    min = 50,
                    max = 1000,
                    step = 50,
                    suffix = " m",
                    onChange = { value -> viewModel.updateSettings { it.copy(maxAccuracyMeters = value) } },
                )
                ToggleRow(
                    label = "Show stay rings",
                    description = "Draw a circle around detected stays on the map.",
                    checked = settings.showStayRings,
                    onCheckedChange = { checked -> viewModel.updateSettings { it.copy(showStayRings = checked) } },
                )
                ToggleRow(
                    label = "Follow location on start",
                    description = "Centre the map on your position when tracking begins.",
                    checked = settings.followOnStart,
                    onCheckedChange = { checked -> viewModel.updateSettings { it.copy(followOnStart = checked) } },
                )
                ToggleRow(
                    label = "Auto photo",
                    description = "Offer a photo prompt when a new visit is recorded.",
                    checked = settings.autoPhoto,
                    onCheckedChange = { checked -> viewModel.updateSettings { it.copy(autoPhoto = checked) } },
                )
            }
        }

        SectionTitle("Night hours")
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "Flashlight controls appear on Home during these hours. Overnight ranges such as 18:00–06:00 are supported.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                HourPicker("Night starts", settings.nightStartHour) { hour ->
                    viewModel.updateSettings { it.copy(nightStartHour = hour) }
                }
                HourPicker("Night ends", settings.nightEndHour) { hour ->
                    viewModel.updateSettings { it.copy(nightEndHour = hour) }
                }
            }
        }

        SectionTitle("Exclude window")
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Samples recorded between these two times are left out of the journal. Set a start, then an end.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                ExcludeTimeRow("Exclude from", settings.excludeStart) { millis ->
                    viewModel.updateSettings { current ->
                        val end = current.excludeEnd
                        current.copy(excludeStart = millis, excludeEnd = if (end != null && end < millis) millis else end)
                    }
                }
                ExcludeTimeRow("Exclude to", settings.excludeEnd) { millis ->
                    viewModel.updateSettings { current ->
                        val start = current.excludeStart
                        current.copy(excludeEnd = millis, excludeStart = if (start != null && start > millis) millis else start)
                    }
                }
                OutlinedButton(
                    modifier = Modifier.fillMaxWidth(),
                    enabled = settings.excludeStart != null || settings.excludeEnd != null,
                    onClick = { viewModel.updateSettings { it.copy(excludeStart = null, excludeEnd = null) } },
                ) { Text("Clear exclude window") }
            }
        }

        SectionTitle("Backup and data")
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Save a copy to a location you choose, or restore a JSON backup.", style = MaterialTheme.typography.bodyMedium)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(
                        modifier = Modifier.weight(1f),
                        onClick = { createJson.launch("location-journal-$stamp.json") },
                    ) { Text("JSON") }
                    FilledTonalButton(
                        modifier = Modifier.weight(1f),
                        onClick = { createGpx.launch("location-journal-$stamp.gpx") },
                    ) { Text("GPX") }
                    FilledTonalButton(
                        modifier = Modifier.weight(1f),
                        onClick = { createCsv.launch("location-journal-$stamp.csv") },
                    ) { Text("CSV") }
                }
                OutlinedButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { openJson.launch(arrayOf("application/json")) },
                ) { Text("Import JSON backup") }
                Text(
                    "Merging keeps what is already here. Replacing deletes current data before the backup is restored.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        SectionTitle("More")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(modifier = Modifier.weight(1f), onClick = { nav.navigate("timeline") }) { Text("History") }
            FilledTonalButton(modifier = Modifier.weight(1f), onClick = { nav.navigate("reels") }) { Text("Reels") }
            FilledTonalButton(modifier = Modifier.weight(1f), onClick = { nav.navigate("home") }) { Text("Home") }
        }

        SectionTitle("Danger zone")
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { confirmDeleteDay = true },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("Delete data for $day") }
                OutlinedButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { confirmClear = true },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("Delete all app data") }
            }
        }
    }

    pendingImport?.let { uri ->
        AlertDialog(
            onDismissRequest = { pendingImport = null },
            title = { Text("Import backup") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Merge keeps the data on this device and adds the backup. Replace clears this device first.")
                    TextButton(onClick = { pendingImport = null }) { Text("Cancel") }
                }
            },
            confirmButton = {
                TextButton(
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    onClick = { viewModel.importBackup(uri, replace = true); pendingImport = null },
                ) { Text("Replace all") }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.importBackup(uri, replace = false); pendingImport = null }) { Text("Merge") }
            },
        )
    }

    if (confirmDeleteDay) {
        AlertDialog(
            onDismissRequest = { confirmDeleteDay = false },
            title = { Text("Delete $day?") },
            text = { Text("Points, visits, photos, and notes for this day will be removed. Other days are kept.") },
            confirmButton = {
                TextButton(
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    onClick = { viewModel.deleteDay(day); confirmDeleteDay = false },
                ) { Text("Delete day") }
            },
            dismissButton = { TextButton(onClick = { confirmDeleteDay = false }) { Text("Cancel") } },
        )
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Delete everything?") },
            text = {
                Text(
                    "This permanently removes all points, visits, places, photos, notes, reels activity, and preferences from this device. " +
                        "It cannot be undone. Export a backup first if you might need it.",
                )
            },
            confirmButton = {
                TextButton(
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    onClick = { viewModel.clearAllData(); confirmClear = false },
                ) { Text("Delete all data") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Keep my data") } },
        )
    }
}

@Composable
private fun <T> ChoiceGroup(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (value, label) ->
            val active = value == selected
            OutlinedButton(
                modifier = Modifier.weight(1f),
                onClick = { onSelect(value) },
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = if (active) MaterialTheme.colorScheme.secondaryContainer else Color.Unspecified,
                    contentColor = if (active) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
                ),
            ) { Text(label) }
        }
    }
}

@Composable
private fun NumberStepper(
    label: String,
    value: Int,
    min: Int,
    max: Int,
    step: Int,
    suffix: String,
    onChange: (Int) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(
                modifier = Modifier.weight(1f),
                enabled = value > min,
                onClick = { onChange((value - step).coerceIn(min, max)) },
            ) { Text("−") }
            Text("$value$suffix", modifier = Modifier.weight(1.4f), style = MaterialTheme.typography.titleMedium)
            FilledTonalButton(
                modifier = Modifier.weight(1f),
                enabled = value < max,
                onClick = { onChange((value + step).coerceIn(min, max)) },
            ) { Text("+") }
        }
    }
}

@Composable
private fun ToggleRow(label: String, description: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun HourPicker(label: String, hour: Int, onSelect: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Column {
            OutlinedButton(onClick = { expanded = true }) { Text(formatHour(hour)) }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier.heightIn(max = 280.dp),
            ) {
                (0..23).forEach { candidate ->
                    DropdownMenuItem(
                        text = { Text(formatHour(candidate)) },
                        onClick = { onSelect(candidate); expanded = false },
                    )
                }
            }
        }
    }
}

@Composable
private fun ExcludeTimeRow(label: String, time: Long?, onChange: (Long) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            if (time == null) "Not set" else LocalDateTime.ofInstant(Instant.ofEpochMilli(time), ZoneId.systemDefault()).format(EXPORT_STAMP_FORMAT),
            style = MaterialTheme.typography.bodyMedium,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(modifier = Modifier.weight(1f), onClick = { onChange(System.currentTimeMillis()) }) { Text("Now") }
            FilledTonalButton(modifier = Modifier.weight(1f), onClick = { onChange((time ?: System.currentTimeMillis()) - HOUR_MS) }) { Text("−1 h") }
            FilledTonalButton(modifier = Modifier.weight(1f), onClick = { onChange((time ?: System.currentTimeMillis()) + HOUR_MS) }) { Text("+1 h") }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(modifier = Modifier.weight(1f), onClick = { onChange((time ?: System.currentTimeMillis()) - DAY_MS) }) { Text("−1 day") }
            OutlinedButton(modifier = Modifier.weight(1f), onClick = { onChange((time ?: System.currentTimeMillis()) + DAY_MS) }) { Text("+1 day") }
        }
    }
}

private fun formatHour(hour: Int): String = String.format(Locale.US, "%02d:00", hour)
