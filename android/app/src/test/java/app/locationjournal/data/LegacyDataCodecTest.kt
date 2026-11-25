package app.locationjournal.data

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class LegacyDataCodecTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun pointStoreSkipsInvalidRowsAndKeepsSource() {
        val text = JSONArray()
            .put(JSONObject().put("id", "sample:1").put("t", 1_000L).put("lat", 27.7172).put("lng", 85.3240).put("accuracy", 20))
            .put(JSONObject().put("id", "bad-time").put("t", 0).put("lat", 27.7).put("lng", 85.3))
            .put(JSONObject().put("id", "bad-lat").put("t", 2_000L).put("lat", "nope").put("lng", 85.3))
            .toString()
        val points = LegacyDataCodec.parsePointStore(text)
        assertEquals(1, points.size)
        assertEquals("sample:1", points.first().id)
        assertEquals("points.json", points.first().source)
        assertEquals(20f, points.first().accuracy)
    }

    @Test
    fun indexedDbParsesPointsPhotosMetaAndReels() {
        val bytes = byteArrayOf(9, 8, 7, 6)
        val json = JSONObject()
            .put("points", JSONArray().put(JSONObject()
                .put("id", "idx:1").put("t", 5_000L).put("lat", 27.72).put("lng", 85.33).put("kind", "hourly").put("hour", "2026-11-25-12")))
            .put("photos", JSONArray().put(JSONObject()
                .put("id", "photo-1").put("name", "old.jpg").put("t", 5_100L)
                .put("lat", 27.72).put("lng", 85.33).put("place", "place-1")
                .put("blob", Base64.encodeToString(bytes, Base64.DEFAULT))))
            .put("meta", JSONArray()
                .put(JSONObject().put("key", "units").put("value", "MI"))
                .put(JSONObject().put("key", "name:stay-1").put("value", "Old home"))
                .put(JSONObject().put("key", "nightStartHour").put("value", 19)))
            .put("reelState", JSONArray()
                .put(JSONObject().put("id", "__prefs").put("index", 3).put("muted", false))
                .put(JSONObject().put("id", "reel-01").put("liked", true).put("likes", 130).put("views", 900)))
            .put("reelComments", JSONArray().put(JSONObject()
                .put("id", "seed-01-a").put("reelId", "reel-01").put("author", "Mina").put("text", "Nice").put("time", 10L).put("mine", false)))

        val parsed = LegacyDataCodec.parseIndexedDb(json, folder.root)
        assertEquals(1, parsed.points.size)
        assertEquals("indexeddb", parsed.points.first().source)
        assertEquals("2026-11-25-12", parsed.points.first().hour)
        assertEquals(1, parsed.photos.size)
        assertEquals("place-1", parsed.photos.first().placeId)
        assertTrue(parsed.photos.first().appPath!!.endsWith(".jpg"))
        assertTrue(java.io.File(parsed.photos.first().appPath!!).exists())
        assertEquals("MI", parsed.settings["units"])
        assertEquals("19", parsed.settings["nightStartHour"])
        assertEquals("Old home", parsed.annotations["name:stay-1"])
        assertEquals("3", parsed.settings["reelIndex"])
        assertEquals("false", parsed.settings["reelMuted"])
        assertEquals(1, parsed.reelStates.size)
        assertEquals(130, parsed.reelStates.first().likes)
        assertEquals("Mina", parsed.reelComments.first().author)
    }

    @Test
    fun errorPayloadProducesEmptyMigration() {
        val parsed = LegacyDataCodec.parseIndexedDb(JSONObject().put("error", "indexeddb"), folder.root)
        assertTrue(parsed.points.isEmpty())
        assertTrue(parsed.photos.isEmpty())
    }
}
