package app.locationjournal.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

class GeoTest {
    private fun point(t: Long, lat: Double = 27.7172, lng: Double = 85.3240, accuracy: Float = 10f) =
        GeoPoint(id = "p:$t", t = t, lat = lat, lng = lng, accuracy = accuracy)

    @Test
    fun haversineMatchesKnownDistance() {
        val paris = LatLng(48.8566, 2.3522)
        val london = LatLng(51.5074, -0.1278)
        val meters = haversineMeters(paris, london)
        assertTrue(meters > 340_000.0)
        assertTrue(meters < 350_000.0)
    }

    @Test
    fun detectStaysGroupsNearbyPointsAndSplitsOnGap() {
        val base = LocalDate.of(2026, 11, 25).atTime(9, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val points = listOf(
            point(base, 27.7172, 85.3240),
            point(base + 10 * 60_000L, 27.71721, 85.32401),
            point(base + 20 * 60_000L, 27.71722, 85.32402),
            point(base + 35 * 60_000L, 27.71719, 85.32399),
            point(base + (35 + 90) * 60_000L, 27.7172, 85.3240),
            point(base + (35 + 100) * 60_000L, 27.7172, 85.3240),
        )
        val stays = detectStays(points)
        assertEquals(1, stays.size)
        assertEquals(4, stays.first().samples)
        assertEquals(35 * 60_000L, stays.first().durationMs)
    }

    @Test
    fun detectStaysIgnoresPoorAccuracy() {
        val base = LocalTime.of(10, 0)
        val t = LocalDate.of(2026, 11, 25).atTime(base).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val stays = detectStays(
            listOf(
                point(t, accuracy = 10f),
                point(t + 35 * 60_000L, accuracy = 10f),
                point(t + 70 * 60_000L, accuracy = 500f),
            )
        )
        assertEquals(1, stays.size)
        assertEquals(2, stays.first().samples)
    }

    @Test
    fun matchPhotosPrefersTimeThenProximity() {
        val t = LocalDate.of(2026, 11, 25).atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val stays = listOf(
            Stay("a", t, t + 40 * 60_000L, 40 * 60_000L, 27.7172, 85.3240, 4),
            Stay("b", t + 3 * 60 * 60_000L, t + 4 * 60 * 60_000L, 60 * 60_000L, 27.7200, 85.3300, 4),
        )
        val photos = listOf(
            PhotoCandidate("in-time", t + 10 * 60_000L, 27.7200, 85.3300),
            PhotoCandidate("near-b", t + 3 * 60 * 60_000L + 10 * 60_000L, 27.7200, 85.3300),
            PhotoCandidate("loose", t + 8 * 60 * 60_000L, 27.9000, 85.9000),
        )
        val match = matchPhotos(stays, photos)
        assertEquals(listOf("in-time"), match.placed["a"])
        assertTrue(match.placed["b"].orEmpty().contains("near-b"))
        assertEquals(listOf("loose"), match.loose)
    }

    @Test
    fun formattingAndSummaryAreStable() {
        assertEquals("950 m", formatDistance(950.0))
        assertEquals("1.25 km", formatDistance(1250.0))
        assertEquals("2 h", formatDuration(2 * 60 * 60_000L + 20_000L))
        assertEquals("1 h 5 min", formatDuration(65 * 60_000L))
        val summary = summarize(
            listOf(point(1000L), point(61_000L, 27.7173, 85.3241)),
            emptyList(),
        )
        assertEquals(2, summary.sampleCount)
        assertEquals(60_000L, summary.trackedMs)
        assertTrue(summary.distanceMeters > 0.0)
    }

    @Test
    fun replayFramesAreTimeOrdered() {
        val frames = buildReplayFrames(listOf(point(2_000L), point(1_000L), point(3_000L)))
        assertEquals(listOf(1_000L, 2_000L, 3_000L), frames.map { it.t })
    }
}
