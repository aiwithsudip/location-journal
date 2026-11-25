package app.locationjournal.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.locationjournal.domain.GeoPoint
import app.locationjournal.domain.Stay
import app.locationjournal.domain.dayKey
import app.locationjournal.domain.formatDistance
import app.locationjournal.domain.formatDuration
import app.locationjournal.domain.formatDate
import app.locationjournal.domain.summarize
import java.time.LocalDate

private data class DayBucket(
    val day: String,
    val pointCount: Int,
    val visitCount: Int,
    val durationMs: Long,
)

@Composable
fun InsightsScreen(viewModel: JournalViewModel) {
    val settings by viewModel.settings.collectAsState()
    val points by viewModel.recentPoints.collectAsState()
    val visits by viewModel.recentVisits.collectAsState()
    val places by viewModel.places.collectAsState()
    val photos by viewModel.photos.collectAsState()
    val entries by viewModel.entries.collectAsState()
    val selectedDay by viewModel.selectedDay.collectAsState()

    val geoPoints = points.map {
        GeoPoint(it.id, it.t, it.lat, it.lng, it.accuracy, it.altitude, it.speed, it.bearing, it.kind, it.hour)
    }
    val stays = visits.map {
        Stay(it.id, it.start, it.end, it.durationMs, it.lat, it.lng, it.samples, it.confidence)
    }
    val totals = summarize(geoPoints, stays)
    val activePlaces = places.filter { it.mergedInto == null }
    val topPlaces = activePlaces.sortedByDescending { it.visitCount }.take(5)
    val geoTagged = photos.count { it.lat != null && it.lng != null }
    val attached = photos.count { it.placeId != null || it.visitId != null }
    val favorites = photos.count { it.favorite }
    val notesWithBody = entries.count { it.body.isNotBlank() }
    val baseDay = runCatching { LocalDate.parse(selectedDay) }.getOrDefault(LocalDate.now())
    val buckets = (0 until 7).map { offset ->
        val day = baseDay.minusDays(offset.toLong()).toString()
        val dayVisits = visits.filter { it.day == day }
        DayBucket(
            day = day,
            pointCount = points.count { dayKey(it.t) == day },
            visitCount = dayVisits.size,
            durationMs = dayVisits.sumOf { it.durationMs },
        )
    }
    val excluded = settings.excludeStart?.let { start ->
        settings.excludeEnd?.let { end -> "${formatDate(start)} – ${formatDate(end)}" }
    } ?: "None"

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Insights", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(
            "A local summary of the data stored on this device.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        StatCard("Recorded distance", formatDistance(totals.distanceMeters, settings.units), Modifier.weight(1f))
                        StatCard("Time span", formatDuration(totals.trackedMs), Modifier.weight(1f))
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        StatCard("Recent visits", visits.size.toString(), Modifier.weight(1f))
                        StatCard("Places", activePlaces.size.toString(), Modifier.weight(1f))
                    }
                }
            }

            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        SectionTitle("Recorded totals")
                        DetailRow("GPS points (recent view)", points.size.toString())
                        DetailRow("Visits (recent view)", visits.size.toString())
                        DetailRow("Unique places", activePlaces.size.toString())
                        DetailRow("Photos", photos.size.toString())
                        DetailRow("Journal entries", entries.size.toString())
                    }
                }
            }

            item { SectionTitle("Last 7 days") }
            items(buckets, key = { it.day }) { bucket ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(bucket.day, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(
                            "${bucket.pointCount} points · ${bucket.visitCount} visits · ${formatDuration(bucket.durationMs)}",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }

            item { SectionTitle("Top places") }
            if (topPlaces.isEmpty()) {
                item { EmptyState("No places recorded", "Places are created after stays are detected in recorded GPS samples.") }
            }
            items(topPlaces, key = { it.id }) { place ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            "${place.name ?: "Unnamed place"} · ${place.category}",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text("${place.visitCount} visits · ${formatDuration(place.totalDurationMs)}", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "Last seen ${if (place.lastSeen > 0L) formatDate(place.lastSeen) else "unknown"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        SectionTitle("Photos and notes")
                        DetailRow("Geotagged photos", "$geoTagged of ${photos.size}")
                        DetailRow("Photos attached to a place or visit", attached.toString())
                        DetailRow("Favorite photos", favorites.toString())
                        DetailRow("Entries with text", "$notesWithBody of ${entries.size}")
                    }
                }
            }

            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        SectionTitle("Recording settings")
                        DetailRow("Distance units", settings.units.name)
                        DetailRow("Sample interval", "${settings.sampleIntervalSeconds} s")
                        DetailRow("Stay radius", "${settings.stayRadiusMeters} m")
                        DetailRow("Minimum stay", formatDuration(settings.stayMinMinutes * 60_000L))
                        DetailRow("Maximum gap", formatDuration(settings.stayMaxGapMinutes * 60_000L))
                        DetailRow("Accepted accuracy", "${settings.maxAccuracyMeters} m")
                        DetailRow("Stay rings on map", if (settings.showStayRings) "On" else "Off")
                        DetailRow("Follow on start", if (settings.followOnStart) "On" else "Off")
                        DetailRow("Automatic photo", if (settings.autoPhoto) "On" else "Off")
                        DetailRow("Night flashlight", "${settings.nightStartHour}:00 – ${settings.nightEndHour}:00")
                        DetailRow("Excluded window", excluded)
                    }
                }
            }

            item {
                val detail = if (totals.incomplete) {
                    "No recent GPS points are available, so distance and time are zero."
                } else {
                    "Distance and time cover only the most recent points shown above."
                }
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Completeness", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(
                            "Totals use the latest 120 GPS points and 60 visits from the live views, plus all saved places, photos, and notes. " +
                                "These are estimates: tracking gaps, denied location permission, and photos without EXIF time or coordinates are not counted. $detail",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
