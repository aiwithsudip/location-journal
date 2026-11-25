package app.locationjournal.data

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

data class ReelMedia(val video: File, val poster: File?)

@Singleton
class ReelMediaStore @Inject constructor(@ApplicationContext private val context: Context) {
    private val dir = File(context.filesDir, "reels").apply { mkdirs() }
    private val mutex = Mutex()
    private var cache: Map<String, ReelMedia>? = null

    suspend fun all(): Map<String, ReelMedia> = mutex.withLock {
        cache?.let { return@withLock it }
        withContext(Dispatchers.IO) {
            val names = context.assets.list("reels").orEmpty()
            val result = names.filter { it.endsWith(".mp4") }.associate { video ->
                val id = video.removeSuffix(".mp4")
                val videoFile = File(dir, video)
                if (!videoFile.exists()) context.assets.open("reels/$video").use { input -> videoFile.outputStream().use { it.write(input.readBytes()) } }
                val posterName = "$id.jpg"
                val posterFile = File(dir, posterName)
                if (names.contains(posterName) && !posterFile.exists()) {
                    context.assets.open("reels/$posterName").use { input -> posterFile.outputStream().use { it.write(input.readBytes()) } }
                }
                id to ReelMedia(videoFile, posterFile.takeIf { it.exists() })
            }
            cache = result
            result
        }
    }
}
