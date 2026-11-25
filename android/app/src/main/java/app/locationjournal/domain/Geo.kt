package app.locationjournal.domain

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

private const val EARTH_RADIUS_M = 6_371_000.0
private val HOUR_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd-HH", Locale.US)
private val DAY_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.US)

fun haversineMeters(a: Coordinates, b: Coordinates): Double {
    val dLat = Math.toRadians(b.lat - a.lat)
    val dLng = Math.toRadians(b.lng - a.lng)
    val lat1 = Math.toRadians(a.lat)
    val lat2 = Math.toRadians(b.lat)
    val h = sin(dLat / 2) * sin(dLat / 2) +
        cos(lat1) * cos(lat2) * sin(dLng / 2) * sin(dLng / 2)
    return 2 * EARTH_RADIUS_M * asin(sqrt(min(1.0, h)))
}

private fun usable(point: GeoPoint, config: StayConfig): Boolean {
    if (!point.lat.isFinite() || !point.lng.isFinite() || point.t <= 0) return false
    return point.accuracy <= config.maxAccuracyMeters
}

private fun centroid(points: List<GeoPoint>): LatLng {
    var lat = 0.0
    var lng = 0.0
    for (point in points) {
        lat += point.lat
        lng += point.lng
    }
    val size = points.size.coerceAtLeast(1)
    return LatLng(lat / size, lng / size)
}

/**
 * Groups consecutive points that remain within [StayConfig.radiusMeters] into visits.
 * Points with poor accuracy are ignored, gaps longer than the configured maximum split a
 * cluster, and clusters need at least two samples and the minimum duration to qualify.
 */
fun detectStays(points: List<GeoPoint>, config: StayConfig = StayConfig()): List<Stay> {
    val sorted = points.filter { usable(it, config) }.sortedBy { it.t }
    val stays = mutableListOf<Stay>()
    var cluster = mutableListOf<GeoPoint>()

    fun flush() {
        if (cluster.size < 2) {
            cluster = mutableListOf()
            return
        }
        val duration = cluster.last().t - cluster.first().t
        if (duration >= config.minDurationMs) {
            val center = centroid(cluster)
            val spread = cluster.maxOf { haversineMeters(center, it) }
            val confidence = (1f - (spread / config.radiusMeters).toFloat()).coerceIn(0.25f, 1f)
            stays += Stay(
                id = "stay-${cluster.first().t}",
                start = cluster.first().t,
                end = cluster.last().t,
                durationMs = duration,
                lat = center.lat,
                lng = center.lng,
                samples = cluster.size,
                confidence = confidence,
            )
        }
        cluster = mutableListOf()
    }

    for (point in sorted) {
        if (cluster.isEmpty()) {
            cluster.add(point)
            continue
        }
        val previous = cluster.last()
        val gap = point.t - previous.t
        val distance = haversineMeters(centroid(cluster), point)
        if (gap > config.maxGapMs || distance > config.radiusMeters) flush()
        cluster.add(point)
    }
    flush()
    return stays
}

/** Route length, ignoring jumps that are long enough to represent a new outing. */
fun pathLengthMeters(points: List<GeoPoint>, maxGapMs: Long = StayConfig().maxGapMs): Double {
    val sorted = points.filter { it.lat.isFinite() && it.lng.isFinite() && it.t > 0 }.sortedBy { it.t }
    var total = 0.0
    for (i in 1 until sorted.size) {
        if (sorted[i].t - sorted[i - 1].t > maxGapMs) continue
        total += haversineMeters(sorted[i - 1], sorted[i])
    }
    return total
}

/** Attaches photos to the best matching visit by time first, then proximity. */
fun matchPhotos(
    stays: List<Stay>,
    photos: List<PhotoCandidate>,
    timeSlackMs: Long = 5 * 60_000L,
    nearMeters: Double = 250.0,
): PhotoMatch {
    val placed = linkedMapOf<String, MutableList<String>>()
    val loose = mutableListOf<String>()
    for (photo in photos) {
        var best: Stay? = null
        var bestScore = Double.MAX_VALUE
        for (stay in stays) {
            val inTime = photo.t != null && photo.t >= stay.start - timeSlackMs && photo.t <= stay.end + timeSlackMs
            val distance = if (photo.lat != null && photo.lng != null) {
                haversineMeters(LatLng(photo.lat, photo.lng), LatLng(stay.lat, stay.lng))
            } else null
            val near = distance != null && distance <= nearMeters
            if (!inTime && !near) continue
            val score = (if (inTime) 0.0 else 100_000.0) + (distance ?: 800.0)
            if (score < bestScore) {
                bestScore = score
                best = stay
            }
        }
        if (best == null) loose += photo.id
        else placed.getOrPut(best.id) { mutableListOf() } += photo.id
    }
    return PhotoMatch(placed.mapValues { it.value.toList() }, loose.toList())
}

fun hourKey(t: Long): String =
    LocalDateTime.ofInstant(Instant.ofEpochMilli(t), ZoneId.systemDefault()).format(HOUR_FORMAT)

fun dayKey(t: Long): String =
    LocalDateTime.ofInstant(Instant.ofEpochMilli(t), ZoneId.systemDefault()).format(DAY_FORMAT)

fun dayBounds(day: String): LongRange {
    val date = LocalDate.parse(day)
    val zone = ZoneId.systemDefault()
    val start = date.atStartOfDay(zone).toInstant().toEpochMilli()
    val end = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    return start until end
}

fun formatDistance(meters: Double, unit: DistanceUnit = DistanceUnit.KM): String {
    if (unit == DistanceUnit.MI) {
        val feet = meters * 3.28084
        return if (feet < 528) "${feet.roundToInt()} ft" else String.format(Locale.US, "%.2f mi", meters / 1609.344)
    }
    return if (meters < 1000) "${meters.roundToInt()} m" else String.format(Locale.US, if (meters < 10_000) "%.2f km" else "%.1f km", meters / 1000)
}

fun formatDuration(ms: Long): String {
    val minutes = (ms / 60_000L).coerceAtLeast(0L).toInt()
    val hours = minutes / 60
    val rest = minutes % 60
    return when {
        hours == 0 -> "$rest min"
        rest == 0 -> "$hours h"
        else -> "$hours h $rest min"
    }
}

fun formatClock(t: Long): String =
    LocalDateTime.ofInstant(Instant.ofEpochMilli(t), ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("h:mm a", Locale.US))

fun formatDate(t: Long): String =
    LocalDateTime.ofInstant(Instant.ofEpochMilli(t), ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("EEE, MMM d", Locale.US))

fun buildReplayFrames(points: List<GeoPoint>): List<ReplayFrame> {
    val sorted = points.filter { it.lat.isFinite() && it.lng.isFinite() && it.t > 0 }.sortedBy { it.t }
    return sorted.map { ReplayFrame(it.t, it.lat, it.lng) }
}

fun summarize(points: List<GeoPoint>, stays: List<Stay>): DistanceSummary {
    val tracked = if (points.size >= 2) points.maxOf { it.t } - points.minOf { it.t } else 0L
    return DistanceSummary(
        distanceMeters = pathLengthMeters(points),
        trackedMs = tracked,
        sampleCount = points.size,
        visitCount = stays.size,
        incomplete = points.isEmpty(),
    )
}
