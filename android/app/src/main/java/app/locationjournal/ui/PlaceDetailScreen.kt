package app.locationjournal.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import app.locationjournal.domain.formatDate
import app.locationjournal.domain.formatDuration
import java.util.Locale

private val PLACE_CATEGORIES = listOf("Home", "Work", "School", "Other")

@Composable
fun PlaceDetailScreen(viewModel: JournalViewModel, placeId: String, nav: NavController) {
    val places by viewModel.places.collectAsState()
    val recentVisits by viewModel.recentVisits.collectAsState()
    val place = places.firstOrNull { it.id == placeId }

    var name by remember(place?.id, place?.name) { mutableStateOf(place?.name.orEmpty()) }
    var category by remember(place?.id, place?.category) { mutableStateOf(place?.category ?: "Other") }
    var editingVisit by remember { mutableStateOf<VisitEntity?>(null) }
    var visitTitle by remember { mutableStateOf("") }
    var visitNote by remember { mutableStateOf("") }
    var deletingDay by remember { mutableStateOf<String?>(null) }

    if (place == null) {
        Column(
            Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Place", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            EmptyState("Place not found", "This place may have been merged or removed. There are no details to show.")
            OutlinedButton(onClick = { nav.navigate("places") }) { Text("Back to places") }
        }
        return
    }

    val visits = recentVisits.filter { it.placeId == place.id }.sortedByDescending { it.start }
    val scroll = rememberScrollState()

    Column(
        Modifier.fillMaxSize().verticalScroll(scroll).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text(place.name ?: "Unnamed place", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text(
                    place.category,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedButton(onClick = { nav.navigate("places") }) { Text("Places") }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                SectionTitle("Place details")
                DetailRow("Coordinates", String.format(Locale.US, "%.5f, %.5f", place.lat, place.lng))
                DetailRow("Visits", "${place.visitCount} · ${formatDuration(place.totalDurationMs)} total")
                DetailRow("First seen", if (place.firstSeen > 0L) formatDate(place.firstSeen) else "Unknown")
                DetailRow("Last seen", if (place.lastSeen > 0L) formatDate(place.lastSeen) else "Unknown")
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionTitle("Edit place")
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                Text("Category", style = MaterialTheme.typography.labelMedium)
                PlaceCategoryPicker(category) { category = it }
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { viewModel.renamePlace(place.id, name, category) },
                ) { Text("Save place") }
            }
        }

        SectionTitle("Visits here (${visits.size})")
        if (visits.isEmpty()) {
            EmptyState("No recent visits", "Visits at this place appear here after a stay is detected from recorded GPS samples.")
        }
        visits.forEach { visit ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(visit.title ?: "Visit", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text("${formatDate(visit.start)} · ${formatClock(visit.start)} – ${formatClock(visit.end)} · ${formatDuration(visit.durationMs)}")
                    Text(
                        "${visit.samples} samples · confidence ${(visit.confidence * 100).toInt()}%",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    visit.note?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            editingVisit = visit
                            visitTitle = visit.title.orEmpty()
                            visitNote = visit.note.orEmpty()
                        }) { Text("Edit visit") }
                        OutlinedButton(onClick = { nav.navigate("map") }) { Text("Map") }
                        OutlinedButton(onClick = { deletingDay = visit.day }) { Text("Delete day") }
                    }
                }
            }
        }
    }

    editingVisit?.let { visit ->
        AlertDialog(
            onDismissRequest = { editingVisit = null },
            title = { Text("Edit visit") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = visitTitle,
                        onValueChange = { visitTitle = it },
                        label = { Text("Title") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = visitNote,
                        onValueChange = { visitNote = it },
                        label = { Text("Note") },
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 4,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.setVisitTitle(visit.id, visitTitle)
                    viewModel.setVisitNote(visit.id, visitNote)
                    editingVisit = null
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { editingVisit = null }) { Text("Cancel") } },
        )
    }

    deletingDay?.let { day ->
        AlertDialog(
            onDismissRequest = { deletingDay = null },
            title = { Text("Delete data for $day?") },
            text = { Text("This removes the points and visits recorded on that day only. Other days are kept.") },
            confirmButton = {
                TextButton(onClick = { viewModel.deleteDay(day); deletingDay = null }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deletingDay = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun PlaceCategoryPicker(selected: String, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Button(onClick = { expanded = true }) { Text(selected) }
    DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
        PLACE_CATEGORIES.forEach { item ->
            DropdownMenuItem(text = { Text(item) }, onClick = { onSelect(item); expanded = false })
        }
    }
}
