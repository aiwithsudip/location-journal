package app.locationjournal.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import app.locationjournal.data.VisitEntity
import app.locationjournal.domain.formatClock
import app.locationjournal.domain.formatDuration
import app.locationjournal.domain.hourKey

@Composable
fun TimelineScreen(viewModel: JournalViewModel, nav: NavController) {
    val visits by viewModel.dayVisits.collectAsState()
    val points by viewModel.dayPoints.collectAsState()
    val places by viewModel.places.collectAsState()
    val day by viewModel.selectedDay.collectAsState()
    var query by remember { mutableStateOf("") }
    var onlyNamed by remember { mutableStateOf(false) }
    var onlyLong by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<VisitEntity?>(null) }
    var note by remember { mutableStateOf("") }
    val placeNames = places.associateBy { it.id }
    val filtered = visits.filter { visit ->
        val haystack = listOfNotNull(visit.title, visit.note, placeNames[visit.placeId]?.name, placeNames[visit.placeId]?.category).joinToString(" ")
        val matches = query.isBlank() || haystack.contains(query.trim(), ignoreCase = true)
        val named = !onlyNamed || placeNames[visit.placeId]?.name?.isNotBlank() == true
        val long = !onlyLong || visit.durationMs >= 30 * 60_000L
        matches && named && long
    }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { viewModel.selectDay(shiftDay(day, -1)) }) { Text("Prev") }
            Text(day, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            OutlinedButton(onClick = { viewModel.selectDay(shiftDay(day, 1)) }) { Text("Next") }
        }
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Search visits") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(onlyNamed, onClick = { onlyNamed = !onlyNamed }, label = { Text("Named") })
            FilterChip(onlyLong, onClick = { onlyLong = !onlyLong }, label = { Text("30 min +") })
        }

        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Text("Visits", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            if (filtered.isEmpty()) {
                item { EmptyState("No visits yet", "Visits appear here after at least 30 minutes of nearby GPS samples.") }
            }
            items(filtered, key = { it.id }) { visit ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(visit.title ?: placeNames[visit.placeId]?.name ?: "Visit", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text("${formatClock(visit.start)} – ${formatClock(visit.end)} · ${formatDuration(visit.durationMs)}")
                        Text("${visit.samples} samples · confidence ${(visit.confidence * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
                        visit.note?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { nav.navigate("place/${visit.placeId}") }) { Text("Place") }
                            OutlinedButton(onClick = { editing = visit; note = visit.note.orEmpty() }) { Text("Note") }
                            OutlinedButton(onClick = { viewModel.deleteDay(day) }) { Text("Delete day") }
                        }
                    }
                }
            }
            item {
                Text("Hourly history", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            items(hourlyRows(points), key = { it.first }) { row ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(row.first, style = MaterialTheme.typography.labelMedium)
                        Text(if (row.second) formatClock(row.third) else "No recorded point · gap in history")
                    }
                }
            }
        }
    }

    editing?.let { visit ->
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("Visit note") },
            text = {
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("What happened here?") },
                    maxLines = 4,
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.setVisitNote(visit.id, note); editing = null }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancel") } },
        )
    }
}

private fun hourlyRows(points: List<app.locationjournal.data.PointEntity>): List<Triple<String, Boolean, Long>> {
    val byHour = linkedMapOf<String, Long>()
    points.filter { it.kind == "hourly" || it.id.startsWith("hourly:") }.sortedBy { it.t }.forEach { byHour[hourKey(it.t)] = it.t }
    if (byHour.isEmpty()) points.sortedBy { it.t }.forEach { byHour[hourKey(it.t)] = it.t }
    return byHour.map { (hour, time) -> Triple(hour, true, time) }
}

private fun shiftDay(day: String, delta: Int): String = java.time.LocalDate.parse(day).plusDays(delta.toLong()).toString()
