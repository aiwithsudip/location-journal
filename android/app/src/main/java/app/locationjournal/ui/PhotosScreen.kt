package app.locationjournal.ui

import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.widget.ImageView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.locationjournal.domain.formatClock
import app.locationjournal.domain.formatDate
import java.util.Locale

@Composable
fun PhotosScreen(viewModel: JournalViewModel) {
    val photos by viewModel.photos.collectAsState()
    val visits by viewModel.recentVisits.collectAsState()
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var attachExpanded by remember { mutableStateOf(false) }

    val pickMedia = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(6)) { uris: List<Uri> ->
        viewModel.importPhotos(uris)
    }
    val getContents = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris: List<Uri> ->
        viewModel.importPhotos(uris)
    }

    fun launchPicker() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        } else {
            getContents.launch("image/*")
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Photos", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(
            "Imported photos stay on this device. Select photos to attach them to a recent visit.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { launchPicker() }, modifier = Modifier.weight(1f)) { Text("Import photos") }
            Button(
                modifier = Modifier.weight(1f),
                enabled = selected.isNotEmpty(),
                onClick = { attachExpanded = true },
            ) { Text("Attach (${selected.size})") }
        }

        DropdownMenu(expanded = attachExpanded, onDismissRequest = { attachExpanded = false }) {
            val recent = visits.sortedByDescending { it.start }.take(12)
            if (recent.isEmpty()) {
                DropdownMenuItem(text = { Text("No visits available yet") }, onClick = { attachExpanded = false }, enabled = false)
            }
            recent.forEach { visit ->
                DropdownMenuItem(
                    text = { Text("${visit.title ?: "Visit"} · ${formatDate(visit.start)} ${formatClock(visit.start)}") },
                    onClick = {
                        viewModel.attachPhotos(selected.toList(), visit.id, visit.placeId)
                        selected = emptySet()
                        attachExpanded = false
                    },
                )
            }
        }

        SectionTitle("Saved photos (${photos.size})")
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (photos.isEmpty()) {
                item { EmptyState("No photos yet", "Use Import photos to add pictures from this device. EXIF time and location are read when available.") }
            }
            items(photos.sortedByDescending { it.t ?: 0L }, key = { it.id }) { photo ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = photo.id in selected,
                                onCheckedChange = { checked ->
                                    selected = if (checked) selected + photo.id else selected - photo.id
                                },
                            )
                            Text(
                                photo.name,
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                        photo.appPath?.let { path ->
                            AndroidView(
                                modifier = Modifier.fillMaxWidth().height(160.dp),
                                factory = { context ->
                                    ImageView(context).apply {
                                        scaleType = ImageView.ScaleType.CENTER_CROP
                                        contentDescription = photo.name
                                    }
                                },
                                update = { imageView ->
                                    val options = BitmapFactory.Options().apply { inSampleSize = 2 }
                                    val bitmap = runCatching { BitmapFactory.decodeFile(path, options) }.getOrNull()
                                    imageView.setImageBitmap(bitmap)
                                },
                            )
                        } ?: Text(
                            "No local preview is stored for this photo.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            "Taken: ${photo.t?.let { "${formatDate(it)} · ${formatClock(it)}" } ?: "unknown"}",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            "Caption: ${photo.caption?.takeIf { it.isNotBlank() } ?: "none"} · Label: ${photo.label?.takeIf { it.isNotBlank() } ?: "none"}",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            "${if (photo.favorite) "Favorite" else "Not favorite"} · " +
                                "place: ${if (photo.placeId == null) "no" else "yes"} · visit: ${if (photo.visitId == null) "no" else "yes"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (photo.lat != null && photo.lng != null) {
                            Text(
                                "Location: ${String.format(Locale.US, "%.5f, %.5f", photo.lat, photo.lng)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}
