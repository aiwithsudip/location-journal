package app.locationjournal.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class BackupCodecTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun validateRejectsBadFilesAndAcceptsLegacyFormat() {
        assertNull(BackupCodec.validate("""{"format":"location-journal-backup","version":1,"points":[]}"""))
        assertTrue(BackupCodec.validate("not json")!!.contains("not a journal backup"))
        assertTrue(BackupCodec.validate("""{"format":"other","version":1,"points":[]}""")!!.contains("not a Location journal"))
        assertTrue(BackupCodec.validate("""{"format":"location-journal-backup","version":9,"points":[]}""")!!.contains("cannot read"))
        assertTrue(BackupCodec.validate("""{"format":"location-journal-backup","version":1}""")!!.contains("no route"))
    }

    @Test
    fun buildParseRoundTripIncludesPhotosAndSettings() {
        val photoFile = folder.newFile("legacy.jpg")
        photoFile.writeBytes(byteArrayOf(1, 2, 3, 4))
        val snapshot = BackupSnapshot(
            points = listOf(PointEntity("p1", 1_700_000_000_000L, 27.7172, 85.3240, 12f, kind = "sample", hour = "2026-11-25-12")),
            photos = listOf(PhotoEntity("photo1", "one.jpg", photoFile.absolutePath, t = 1_700_000_001_000L, lat = 27.7172, lng = 85.3240, caption = "caption", favorite = true)),
            places = listOf(PlaceEntity("place1", 27.7172, 85.3240, name = "Home", visitCount = 3)),
            visits = listOf(VisitEntity("v1", "place1", "2026-11-25", 1_700_000_000_000L, 1_700_000_100_000L, 100_000L, 27.7172, 85.3240, 4)),
            entries = listOf(JournalEntryEntity("e1", "Title", "Body", "Home", 1_700_000_000_000L, null, null, 1_700_000_000_000L, 1_700_000_000_000L)),
            reelStates = listOf(ReelStateEntity("reel-01", liked = true, likes = 129)),
            reelComments = listOf(ReelCommentEntity("c1", "reel-01", "You", "Nice", 42L, true)),
            settings = AppSettings(),
        )
        val text = BackupCodec.build(snapshot, mapOf("units" to "MI", "theme" to "DARK"))
        val parsed = BackupCodec.parse(text, folder.root)

        assertNull(BackupCodec.validate(text))
        assertEquals(1, parsed.points.size)
        assertEquals(85.3240, parsed.points.first().lng, 0.000001)
        assertEquals(1, parsed.photos.size)
        assertEquals("caption", parsed.photos.first().caption)
        assertEquals(true, parsed.photos.first().favorite)
        assertTrue(parsed.photos.first().appPath!!.endsWith(".jpg"))
        assertEquals("MI", parsed.settings["units"])
        assertEquals("DARK", parsed.settings["theme"])
        assertEquals(1, parsed.reelStates.size)
        assertEquals(129, parsed.reelStates.first().likes)
        assertEquals(1, parsed.reelComments.size)
        assertEquals("Nice", parsed.reelComments.first().text)
    }

    @Test
    fun gpxAndCsvExportsAreMachineReadable() {
        val points = listOf(
            PointEntity("p2", 2_000L, 27.71, 85.32, 5f, kind = "sample"),
            PointEntity("p1", 1_000L, 27.70, 85.31, 6f, kind = "sample"),
        )
        val gpx = BackupCodec.gpx(points)
        assertTrue(gpx.startsWith("<?xml"))
        assertTrue(gpx.indexOf("lat=\"27.7\"") < gpx.indexOf("lat=\"27.71\""))
        val csv = BackupCodec.csv(points)
        assertEquals("time,latitude,longitude,accuracy,kind", csv.lines().first())
        assertEquals("1000", csv.lines()[1].substringBefore(','))
    }
}
