package app.locationjournal.data

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** Reads the legacy native points.json and WebView IndexedDB into Room exactly once. */
@Singleton
class LegacyImporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: JournalRepository,
    private val preferences: AppPreferences,
    private val exporter: LegacyWebViewExporter,
) {
    private val photoDir = File(context.filesDir, "photos").apply { mkdirs() }

    suspend fun importIfNeeded() {
        if (repository.settings.first().legacyImportComplete) return
        val points = LinkedHashMap<String, PointEntity>()
        val photos = LinkedHashMap<String, PhotoEntity>()
        val settings = LinkedHashMap<String, String>()
        val annotations = LinkedHashMap<String, String>()
        val reelStates = LinkedHashMap<String, ReelStateEntity>()
        val reelComments = LinkedHashMap<String, ReelCommentEntity>()

        val pointStore = File(context.filesDir, "points.json")
        if (pointStore.exists()) {
            runCatching { pointStore.readText() }
                .getOrNull()
                ?.let { LegacyDataCodec.parsePointStore(it) }
                ?.forEach { points[it.id] = it }
        }

        val parsed = LegacyDataCodec.parseIndexedDb(exporter.export(), photoDir)
        parsed.points.forEach { points[it.id] = it }
        parsed.photos.forEach { photos[it.id] = it }
        settings.putAll(parsed.settings)
        annotations.putAll(parsed.annotations)
        parsed.reelStates.forEach { reelStates[it.id] = it }
        parsed.reelComments.forEach { reelComments[it.id] = it }

        if (points.isNotEmpty() || photos.isNotEmpty() || settings.isNotEmpty()) {
            repository.importLegacy(
                LegacyData(
                    points = points.values.toList(),
                    photos = photos.values.toList(),
                    settings = settings.toMap(),
                    annotations = annotations.toMap(),
                    reelStates = reelStates.values.toList(),
                    reelComments = reelComments.values.toList(),
                )
            )
            annotations.forEach { (key, value) ->
                if (key.startsWith("name:")) repository.setVisitTitle(key.removePrefix("name:"), value)
            }
        }
        preferences.markLegacyImportComplete()
    }
}
