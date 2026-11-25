package app.locationjournal.domain

/** Minimal coordinate contract shared by points, stays, and photo candidates. */
interface Coordinates {
    val lat: Double
    val lng: Double
}

data class LatLng(override val lat: Double, override val lng: Double) : Coordinates

data class GeoPoint(
    val id: String,
    val t: Long,
    override val lat: Double,
    override val lng: Double,
    val accuracy: Float = 0f,
    val altitude: Double? = null,
    val speed: Float? = null,
    val bearing: Float? = null,
    val kind: String = "sample",
    val hour: String? = null,
) : Coordinates {
    fun toLatLng(): LatLng = LatLng(lat, lng)
}

data class StayConfig(
    val radiusMeters: Double = 200.0,
    val minDurationMs: Long = 30L * 60_000L,
    val maxGapMs: Long = 70L * 60_000L,
    val maxAccuracyMeters: Float = 250f,
)

data class Stay(
    val id: String,
    val start: Long,
    val end: Long,
    val durationMs: Long,
    override val lat: Double,
    override val lng: Double,
    val samples: Int,
    val confidence: Float = 1f,
) : Coordinates {
    fun toLatLng(): LatLng = LatLng(lat, lng)
}

data class PhotoCandidate(
    val id: String,
    val t: Long?,
    val lat: Double?,
    val lng: Double?,
)

data class PhotoMatch(val placed: Map<String, List<String>>, val loose: List<String>)

data class DistanceSummary(
    val distanceMeters: Double,
    val trackedMs: Long,
    val sampleCount: Int,
    val visitCount: Int,
    val incomplete: Boolean,
)

data class ReplayFrame(val t: Long, val lat: Double, val lng: Double)

enum class DistanceUnit { KM, MI }
enum class ThemeMode { SYSTEM, LIGHT, DARK }
