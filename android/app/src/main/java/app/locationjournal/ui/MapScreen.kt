package app.locationjournal.ui

import android.content.Context
import android.net.ConnectivityManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.locationjournal.data.VisitEntity
import app.locationjournal.domain.formatClock
import app.locationjournal.domain.formatDistance
import kotlinx.coroutines.delay
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint as OsmPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.Polyline
import java.io.File

@Composable
fun MapScreen(viewModel: JournalViewModel) {
    val context = LocalContext.current
    val points by viewModel.dayPoints.collectAsState()
    val visits by viewModel.dayVisits.collectAsState()
    val settings by viewModel.settings.collectAsState()
    val day by viewModel.selectedDay.collectAsState()
    val summary by viewModel.summary.collectAsState()
    var satellite by remember { mutableStateOf(false) }
    var playing by remember { mutableStateOf(false) }
    var speed by remember { mutableIntStateOf(1) }
    var replayIndex by remember { mutableIntStateOf(-1) }
    var selectedVisit by remember { mutableStateOf<VisitEntity?>(null) }
    val online = remember(context) {
        runCatching {
            val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            @Suppress("DEPRECATION") manager.activeNetworkInfo?.isConnected == true
        }.getOrDefault(false)
    }

    LaunchedEffect(playing, points.size, speed) {
        if (!playing || points.isEmpty()) return@LaunchedEffect
        while (replayIndex < points.lastIndex) {
            replayIndex += 1
            delay(350L / speed)
        }
        playing = false
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { viewModel.selectDay(shiftDay(day, -1)) }) { Text("Prev") }
            Text(day, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            OutlinedButton(onClick = { viewModel.selectDay(shiftDay(day, 1)) }) { Text("Next") }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(satellite, onClick = { satellite = !satellite }, label = { Text(if (satellite) "Satellite" else "Street") })
            Button(onClick = { playing = !playing; if (replayIndex >= points.lastIndex) replayIndex = -1 }) {
                Text(if (playing) "Pause replay" else "Replay")
            }
            OutlinedButton(onClick = { speed = if (speed == 1) 2 else if (speed == 2) 4 else 1 }) { Text("${speed}x") }
            OutlinedButton(onClick = { replayIndex = -1; playing = false }) { Text("Reset") }
        }
        if (!online) {
            Text(
                "Offline: map tiles are unavailable. Saved routes and visits still work.",
                Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Text(
            "${formatDistance(summary.distanceMeters, settings.units)} · ${summary.sampleCount} points · ${visits.size} visits",
            Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            style = MaterialTheme.typography.bodyMedium,
        )

        AndroidView(
            modifier = Modifier.fillMaxSize().weight(1f),
            factory = { ctx -> buildMap(ctx) },
            update = { map ->
                map.setTileSource(if (satellite) TileSourceFactory.USGS_SAT else TileSourceFactory.MAPNIK)
                map.overlays.clear()
                val geo = points.map { OsmPoint(it.lat, it.lng) }
                if (geo.isNotEmpty()) {
                    map.overlays.add(Polyline(map).apply {
                        setPoints(geo)
                        outlinePaint.color = android.graphics.Color.parseColor("#B5522A")
                        outlinePaint.strokeWidth = 7f
                    })
                    map.overlays.add(marker(map, geo.first(), "Start") {})
                    map.overlays.add(marker(map, geo.last(), "End") {})
                    val replay = if (replayIndex in geo.indices) replayIndex else -1
                    if (replay >= 0) {
                        map.overlays.add(marker(map, geo[replay], "Replay · ${formatClock(points[replay].t)}") {})
                        map.controller.setCenter(geo[replay])
                        map.controller.setZoom(17.0)
                    }
                }
                visits.forEach { visit ->
                    if (settings.showStayRings) {
                        val center = OsmPoint(visit.lat, visit.lng)
                        map.overlays.add(Polygon(map).apply {
                            setPoints(Polygon.pointsAsCircle(center, settings.stayRadiusMeters.toDouble()))
                            fillPaint.color = android.graphics.Color.argb(40, 181, 82, 42)
                            outlinePaint.color = android.graphics.Color.parseColor("#B5522A")
                            outlinePaint.strokeWidth = 2f
                        })
                    }
                    map.overlays.add(marker(map, OsmPoint(visit.lat, visit.lng), visit.title ?: "Visit") { selectedVisit = visit })
                }
                if (replayIndex < 0 && geo.isNotEmpty()) {
                    val box = org.osmdroid.util.BoundingBox.fromGeoPointsSafe(geo)
                    map.zoomToBoundingBox(box, false, 64)
                }
                map.invalidate()
            },
        )
    }

    selectedVisit?.let { visit ->
        AlertDialog(
            onDismissRequest = { selectedVisit = null },
            title = { Text(visit.title ?: "Visit") },
            text = {
                Column {
                    Text("${formatClock(visit.start)} – ${formatClock(visit.end)}")
                    Text("${formatDurationText(visit.durationMs)} · ${visit.samples} points")
                    visit.note?.takeIf { it.isNotBlank() }?.let { Text(it) }
                }
            },
            confirmButton = { TextButton(onClick = { selectedVisit = null }) { Text("Close") } },
        )
    }
}

private fun buildMap(context: Context): MapView {
    Configuration.getInstance().userAgentValue = context.packageName
    Configuration.getInstance().osmdroidBasePath = File(context.cacheDir, "osmdroid")
    Configuration.getInstance().osmdroidTileCache = File(File(context.cacheDir, "osmdroid"), "tiles")
    return MapView(context).apply {
        setTileSource(TileSourceFactory.MAPNIK)
        setMultiTouchControls(true)
        controller.setZoom(15.0)
    }
}

private fun marker(map: MapView, point: OsmPoint, title: String, onClick: () -> Unit): Marker =
    Marker(map).apply {
        position = point
        this.title = title
        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
        setOnMarkerClickListener { _, _ -> onClick(); true }
    }

private fun shiftDay(day: String, delta: Int): String = java.time.LocalDate.parse(day).plusDays(delta.toLong()).toString()
private fun formatDurationText(ms: Long): String = app.locationjournal.domain.formatDuration(ms)
