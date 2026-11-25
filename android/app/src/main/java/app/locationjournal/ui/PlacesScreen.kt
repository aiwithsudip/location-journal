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
import app.locationjournal.data.PlaceEntity
import app.locationjournal.domain.formatDuration

private val CATEGORIES = listOf("Home", "Work", "School", "Other")

@Composable
fun PlacesScreen(viewModel: JournalViewModel, nav: NavController) {
    val places by viewModel.places.collectAsState()
    var editing by remember { mutableStateOf<PlaceEntity?>(null) }
    var name by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("Other") }
    var merging by remember { mutableStateOf<PlaceEntity?>(null) }
    var target by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Places", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text("Frequent stops are grouped automatically. Rename them to make your journal easier to read.", style = MaterialTheme.typography.bodyMedium)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (places.isEmpty()) item { EmptyState("No places yet", "Places appear after visits are detected from recorded GPS samples.") }
            items(places, key = { it.id }) { place ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(place.name ?: "Unnamed place", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text("${place.category} · ${place.visitCount} visits · ${formatDuration(place.totalDurationMs)}")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { nav.navigate("place/${place.id}") }) { Text("Details") }
                            OutlinedButton(onClick = {
                                editing = place; name = place.name.orEmpty(); category = place.category
                            }) { Text("Edit") }
                            OutlinedButton(onClick = { merging = place; target = "" }) { Text("Merge") }
                        }
                    }
                }
            }
        }
    }

    editing?.let { place ->
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("Edit place") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true)
                    Text("Category", style = MaterialTheme.typography.labelMedium)
                    CategoryPicker(category) { category = it }
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.renamePlace(place.id, name, category); editing = null }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancel") } },
        )
    }

    merging?.let { source ->
        AlertDialog(
            onDismissRequest = { merging = null },
            title = { Text("Merge duplicate place") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Merge \"${source.name ?: "Unnamed"}\" into:")
                    places.filter { it.id != source.id }.forEach { candidate ->
                        TextButton(onClick = { target = candidate.id }) {
                            Text("${candidate.name ?: "Unnamed"} (${candidate.visitCount} visits)")
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = target.isNotBlank(),
                    onClick = { viewModel.mergePlaces(source.id, target); merging = null },
                ) { Text("Merge") }
            },
            dismissButton = { TextButton(onClick = { merging = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun CategoryPicker(selected: String, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Row {
        Button(onClick = { expanded = true }) { Text(selected) }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            CATEGORIES.forEach { item ->
                DropdownMenuItem(text = { Text(item) }, onClick = { onSelect(item); expanded = false })
            }
        }
    }
}
