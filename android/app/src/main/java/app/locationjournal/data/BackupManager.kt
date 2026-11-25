package app.locationjournal.data

import android.content.Context
import android.net.Uri
import android.util.Base64
import app.locationjournal.domain.formatDate
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

object BackupCodec {
    const val FORMAT = "location-journal-backup"

    fun validate(text: String): String? {
        val json = runCatching { JSONObject(text) }.getOrElse { return "That file is not a journal backup." }
        if (json.optString("format") != FORMAT) return "That file is not a Location journal backup."
        val version = json.optInt("version", -1)
        if (version !in 1..2) return "This app cannot read that backup version."
        if (json.optJSONArray("points") == null) return "The backup has no route."
        return null
    }

    fun parse(text: String, photoDir: File): LegacyData {
        val json = JSONObject(text)
        val points = JSONArrayHelper.objects(json.optJSONArray("points")).mapNotNull { row ->
            val lat = row.optDouble("lat", Double.NaN)
            val lng = row.optDouble("lng", Double.NaN)
            val t = row.optLong("t", 0L)
            if (!lat.isFinite() || !lng.isFinite() || t <= 0) return@mapNotNull null
            PointEntity(
                id = row.optString("id", "sample:$t"),
                t = t,
                lat = lat,
                lng = lng,
                accuracy = row.optDouble("accuracy", 0.0).toFloat(),
                kind = row.optString("kind", "sample"),
                hour = row.optString("hour").takeIf { it.isNotBlank() },
            )
        }
        val photos = JSONArrayHelper.objects(json.optJSONArray("photos")).mapNotNull { row ->
            val id = row.optString("id")
            if (id.isBlank()) return@mapNotNull null
            val encoded = row.optString("blob").takeIf { it.isNotBlank() }
            var path: String? = row.optString("appPath").takeIf { it.isNotBlank() }
            if (encoded != null && path == null) {
                val file = File(photoDir, "${UUID.nameUUIDFromBytes(id.toByteArray())}.jpg")
                runCatching {
                    val bytes = Base64.decode(encoded, Base64.DEFAULT)
                    if (bytes.isNotEmpty()) {
                        file.writeBytes(bytes)
                        path = file.absolutePath
                    }
                }
            }
            PhotoEntity(
                id = id,
                name = row.optString("name", "photo"),
                appPath = path,
                t = row.optLong("t", 0L).takeIf { it > 0 },
                lat = row.optDouble("lat", Double.NaN).takeIf { it.isFinite() },
                lng = row.optDouble("lng", Double.NaN).takeIf { it.isFinite() },
                caption = row.optString("caption").takeIf { it.isNotBlank() },
                label = row.optString("label").takeIf { it.isNotBlank() },
                favorite = row.optBoolean("favorite", false),
                placeId = row.optString("placeId").takeIf { it.isNotBlank() },
                visitId = row.optString("visitId").takeIf { it.isNotBlank() },
            )
        }
        val reelStates = JSONArrayHelper.objects(json.optJSONArray("reelStates")).map { row ->
            ReelStateEntity(
                id = row.optString("id"),
                liked = row.optBoolean("liked"),
                saved = row.optBoolean("saved"),
                likes = row.optInt("likes"),
                views = row.optInt("views"),
                shares = row.optInt("shares"),
                reshares = row.optInt("reshares"),
                index = row.optInt("index"),
                muted = row.optBoolean("muted", true),
            )
        }
        val reelComments = JSONArrayHelper.objects(json.optJSONArray("reelComments")).map { row ->
            ReelCommentEntity(
                id = row.optString("id", UUID.randomUUID().toString()),
                reelId = row.optString("reelId"),
                author = row.optString("author", "You"),
                text = row.optString("text", ""),
                time = row.optLong("time", System.currentTimeMillis()),
                mine = row.optBoolean("mine", true),
            )
        }
        val settings = JSONObjectHelper.strings(json.optJSONObject("settings"))
        val annotations = JSONObjectHelper.strings(json.optJSONObject("annotations"))
        return LegacyData(points, photos, settings, annotations, reelStates, reelComments)
    }

    fun build(snapshot: BackupSnapshot, settingsMap: Map<String, String>): String {
        val root = JSONObject()
        root.put("format", FORMAT)
        root.put("version", 2)
        root.put("exportedAt", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).format(System.currentTimeMillis()))
        root.put("settings", JSONObject(settingsMap))
        root.put("annotations", JSONObject())
        val points = JSONArray()
        snapshot.points.forEach {
            points.put(
                JSONObject().apply {
                    put("id", it.id); put("t", it.t); put("lat", it.lat); put("lng", it.lng)
                    put("accuracy", it.accuracy.toDouble()); put("kind", it.kind); put("hour", it.hour)
                }
            )
        }
        root.put("points", points)
        val photos = JSONArray()
        snapshot.photos.forEach {
            val row = JSONObject().apply {
                put("id", it.id); put("name", it.name); put("t", it.t ?: JSONObject.NULL)
                put("lat", it.lat ?: JSONObject.NULL); put("lng", it.lng ?: JSONObject.NULL)
                put("caption", it.caption ?: JSONObject.NULL); put("label", it.label ?: JSONObject.NULL)
                put("favorite", it.favorite); put("placeId", it.placeId ?: JSONObject.NULL)
                put("visitId", it.visitId ?: JSONObject.NULL)
            }
            it.appPath?.let { path ->
                runCatching {
                    val file = File(path)
                    if (file.exists()) row.put("blob", Base64.encodeToString(file.readBytes(), Base64.DEFAULT))
                }
            }
            photos.put(row)
        }
        root.put("photos", photos)
        val places = JSONArray()
        snapshot.places.forEach {
            places.put(JSONObject().apply {
                put("id", it.id); put("lat", it.lat); put("lng", it.lng); put("name", it.name ?: JSONObject.NULL)
                put("category", it.category); put("visitCount", it.visitCount); put("totalDurationMs", it.totalDurationMs)
            })
        }
        root.put("places", places)
        val visits = JSONArray()
        snapshot.visits.forEach {
            visits.put(JSONObject().apply {
                put("id", it.id); put("placeId", it.placeId); put("day", it.day); put("start", it.start)
                put("end", it.end); put("durationMs", it.durationMs); put("lat", it.lat); put("lng", it.lng)
                put("samples", it.samples); put("confidence", it.confidence.toDouble()); put("title", it.title ?: JSONObject.NULL)
                put("note", it.note ?: JSONObject.NULL)
            })
        }
        root.put("visits", visits)
        val entries = JSONArray()
        snapshot.entries.forEach {
            entries.put(JSONObject().apply {
                put("id", it.id); put("title", it.title); put("body", it.body); put("category", it.category ?: JSONObject.NULL)
                put("time", it.time); put("placeId", it.placeId ?: JSONObject.NULL); put("visitId", it.visitId ?: JSONObject.NULL)
            })
        }
        root.put("entries", entries)
        val reelStates = JSONArray()
        snapshot.reelStates.forEach {
            reelStates.put(JSONObject().apply {
                put("id", it.id); put("liked", it.liked); put("saved", it.saved); put("likes", it.likes)
                put("views", it.views); put("shares", it.shares); put("reshares", it.reshares); put("index", it.index); put("muted", it.muted)
            })
        }
        root.put("reelStates", reelStates)
        val comments = JSONArray()
        snapshot.reelComments.forEach {
            comments.put(JSONObject().apply {
                put("id", it.id); put("reelId", it.reelId); put("author", it.author); put("text", it.text); put("time", it.time); put("mine", it.mine)
            })
        }
        root.put("reelComments", comments)
        return root.toString()
    }

    fun gpx(points: List<PointEntity>): String {
        val rows = points.filter { it.lat.isFinite() && it.lng.isFinite() }.sortedBy { it.t }.joinToString("") {
            val time = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).format(it.t)
            "<trkpt lat=\"${it.lat}\" lon=\"${it.lng}\"><time>$time</time></trkpt>"
        }
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<gpx version=\"1.1\" creator=\"Location journal\"><trk><name>Location journal</name><trkseg>$rows</trkseg></trk></gpx>"
    }

    fun csv(points: List<PointEntity>): String = buildString {
        append("time,latitude,longitude,accuracy,kind\n")
        points.sortedBy { it.t }.forEach {
            append(it.t).append(',')
                .append(String.format(Locale.US, "%.6f", it.lat)).append(',')
                .append(String.format(Locale.US, "%.6f", it.lng)).append(',')
                .append(it.accuracy.round()).append(',')
                .append(it.kind).append('\n')
        }
    }

    private fun Float.round(): String = String.format(Locale.US, "%.1f", this)
}

private object JSONArrayHelper {
    fun objects(array: JSONArray?): List<JSONObject> = (0 until (array?.length() ?: 0)).mapNotNull { array?.optJSONObject(it) }
}

private object JSONObjectHelper {
    fun strings(obj: JSONObject?): Map<String, String> {
        if (obj == null) return emptyMap()
        return (0 until obj.length()).associate { key ->
            val name = obj.names()?.optString(key).orEmpty()
            name to obj.optString(name, "")
        }
    }
}

@Singleton
class BackupManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: JournalRepository,
) {
    private val photoDir = File(context.filesDir, "photos").apply { mkdirs() }

    suspend fun exportJson(uri: Uri) {
        val snapshot = repository.snapshot()
        val settings = mapOf(
            "units" to snapshot.settings.units.name,
            "theme" to snapshot.settings.theme.name,
            "nightStartHour" to snapshot.settings.nightStartHour.toString(),
            "nightEndHour" to snapshot.settings.nightEndHour.toString(),
        )
        write(uri, BackupCodec.build(snapshot, settings))
    }

    suspend fun exportGpx(uri: Uri) = write(uri, BackupCodec.gpx(repository.snapshot().points))

    suspend fun exportCsv(uri: Uri) = write(uri, BackupCodec.csv(repository.snapshot().points))

    suspend fun importJson(uri: Uri, replace: Boolean): String {
        val text = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            ?: return "Could not read that file."
        val error = BackupCodec.validate(text)
        if (error != null) return error
        val data = BackupCodec.parse(text, photoDir)
        repository.importBackup(data, replace)
        return "Imported ${data.points.size} points and ${data.photos.size} photos."
    }

    private fun write(uri: Uri, text: String) {
        context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
            ?: throw IllegalStateException("Could not write to the selected location.")
    }
}
