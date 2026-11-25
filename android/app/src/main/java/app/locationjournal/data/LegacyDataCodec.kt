package app.locationjournal.data

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Pure parsing helpers for the old native point file and WebView IndexedDB stores. */
object LegacyDataCodec {
    data class Parsed(
        val points: List<PointEntity>,
        val photos: List<PhotoEntity>,
        val settings: Map<String, String>,
        val annotations: Map<String, String>,
        val reelStates: List<ReelStateEntity>,
        val reelComments: List<ReelCommentEntity>,
    )

    fun parsePointStore(text: String): List<PointEntity> {
        val array = runCatching { JSONArray(text) }.getOrElse { return emptyList() }
        return (0 until array.length()).mapNotNull { index ->
            val row = array.optJSONObject(index) ?: return@mapNotNull null
            val t = row.optLong("t", 0L)
            val lat = row.optDouble("lat", Double.NaN)
            val lng = row.optDouble("lng", Double.NaN)
            if (t <= 0 || !lat.isFinite() || !lng.isFinite()) return@mapNotNull null
            PointEntity(
                id = row.optString("id", "sample:$t"),
                t = t,
                lat = lat,
                lng = lng,
                accuracy = row.optDouble("accuracy", 0.0).toFloat(),
                kind = row.optString("kind", "sample"),
                hour = row.optString("hour").takeIf { it.isNotBlank() },
                source = "points.json",
            )
        }
    }

    fun parseIndexedDb(json: JSONObject, photoDir: File): Parsed {
        if (json.optString("error").isNotBlank()) return Parsed(emptyList(), emptyList(), emptyMap(), emptyMap(), emptyList(), emptyList())

        val points = mutableListOf<PointEntity>()
        json.optJSONArray("points").forEachObject { row ->
            val t = row.optLong("t", 0L)
            val lat = row.optDouble("lat", Double.NaN)
            val lng = row.optDouble("lng", Double.NaN)
            if (t <= 0 || !lat.isFinite() || !lng.isFinite()) return@forEachObject
            points += PointEntity(
                id = row.optString("id", "sample:$t"),
                t = t,
                lat = lat,
                lng = lng,
                accuracy = row.optDouble("accuracy", 0.0).toFloat(),
                kind = row.optString("kind", "sample"),
                hour = row.optString("hour").takeIf { it.isNotBlank() },
                source = "indexeddb",
            )
        }

        val photos = mutableListOf<PhotoEntity>()
        json.optJSONArray("photos").forEachObject { row ->
            val id = row.optString("id")
            if (id.isBlank()) return@forEachObject
            val encoded = row.optString("blob").takeIf { it.isNotBlank() }
            var path: String? = null
            if (encoded != null) {
                runCatching {
                    val bytes = Base64.decode(encoded, Base64.DEFAULT)
                    if (bytes.isNotEmpty()) {
                        val file = File(photoDir, "${UUID.nameUUIDFromBytes(id.toByteArray())}.jpg")
                        file.writeBytes(bytes)
                        path = file.absolutePath
                    }
                }
            }
            photos += PhotoEntity(
                id = id,
                name = row.optString("name", "photo"),
                appPath = path,
                t = row.optLong("t", 0L).takeIf { it > 0 },
                lat = row.optDouble("lat", Double.NaN).takeIf { it.isFinite() },
                lng = row.optDouble("lng", Double.NaN).takeIf { it.isFinite() },
                caption = row.optString("caption").takeIf { it.isNotBlank() },
                label = row.optString("label").takeIf { it.isNotBlank() },
                favorite = row.optBoolean("favorite", false),
                placeId = row.optString("place").takeIf { it.isNotBlank() },
            )
        }

        val settings = linkedMapOf<String, String>()
        val annotations = linkedMapOf<String, String>()
        json.optJSONArray("meta").forEachObject { row ->
            val key = row.optString("key")
            if (key.isBlank()) return@forEachObject
            val value = when (val raw = row.opt("value")) {
                is JSONObject -> raw.toString()
                is JSONArray -> raw.toString()
                is Boolean, is Number -> raw.toString()
                is String -> raw
                null -> ""
                else -> raw.toString()
            }
            if (key.startsWith("name:")) annotations[key] = value else settings[key] = value
        }

        val reelStates = mutableListOf<ReelStateEntity>()
        json.optJSONArray("reelState").forEachObject { row ->
            val id = row.optString("id")
            if (id.isBlank()) return@forEachObject
            if (id == "__prefs") {
                settings["reelIndex"] = row.optInt("index", 0).toString()
                settings["reelMuted"] = row.optBoolean("muted", true).toString()
            } else {
                reelStates += ReelStateEntity(
                    id = id,
                    liked = row.optBoolean("liked"),
                    saved = row.optBoolean("saved"),
                    likes = row.optInt("likes"),
                    views = row.optInt("views"),
                    shares = row.optInt("shares"),
                    reshares = row.optInt("reshares"),
                    index = row.optInt("index", 0),
                    muted = row.optBoolean("muted", true),
                )
            }
        }

        val reelComments = mutableListOf<ReelCommentEntity>()
        json.optJSONArray("reelComments").forEachObject { row ->
            val id = row.optString("id", UUID.randomUUID().toString())
            reelComments += ReelCommentEntity(
                id = id,
                reelId = row.optString("reelId"),
                author = row.optString("author", "You"),
                text = row.optString("text", ""),
                time = row.optLong("time", System.currentTimeMillis()),
                mine = row.optBoolean("mine", false),
            )
        }

        return Parsed(points, photos, settings.toMap(), annotations.toMap(), reelStates, reelComments)
    }

    private fun JSONArray?.forEachObject(block: (JSONObject) -> Unit) {
        if (this == null) return
        for (i in 0 until length()) optJSONObject(i)?.let(block)
    }
}
