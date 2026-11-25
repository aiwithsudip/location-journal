package app.locationjournal.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.exifinterface.media.ExifInterface
import app.locationjournal.domain.PhotoCandidate
import app.locationjournal.domain.Stay
import app.locationjournal.domain.matchPhotos
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

data class PhotoImportResult(val imported: Int, val duplicates: Int, val skipped: Int)

@Singleton
class PhotoImporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: JournalRepository,
) {
    private val photoDir = File(context.filesDir, "photos").apply { mkdirs() }

    suspend fun import(uris: List<Uri>): PhotoImportResult = withContext(Dispatchers.IO) {
        val existing = repository.observePhotos().first().map { it.id }.toSet()
        val seen = existing.toMutableSet()
        val newPhotos = mutableListOf<PhotoEntity>()
        var skipped = 0
        var duplicates = 0

        for (uri in uris) {
            val meta = displayMeta(uri)
            if (meta == null) {
                skipped += 1
                continue
            }
            val (name, size, modified) = meta
            val id = "$name:$size:$modified"
            if (!seen.add(id)) {
                duplicates += 1
                continue
            }
            val exif = runCatching { ExifInterface(context.contentResolver.openInputStream(uri)!!) }.getOrNull()
            val taken = exif?.let { readTime(it.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)) }
                ?: exif?.let { readTime(it.getAttribute(ExifInterface.TAG_DATETIME)) }
                ?: modified.takeIf { it > 0 }
            val latLng = exif?.latLong?.takeIf { it.size == 2 && it[0].isFinite() && it[1].isFinite() }
            val file = File(photoDir, "${UUID.nameUUIDFromBytes(id.toByteArray())}.img")
            val copied = runCatching {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    file.outputStream().use { output -> input.copyTo(output, 64 * 1024) }
                } != null
            }.getOrDefault(false)
            if (!copied) {
                skipped += 1
                continue
            }
            newPhotos += PhotoEntity(
                id = id,
                name = name,
                appPath = file.absolutePath,
                sourceUri = uri.toString(),
                t = taken,
                lat = latLng?.get(0),
                lng = latLng?.get(1),
            )
        }

        if (newPhotos.isNotEmpty()) {
            repository.upsertPhotos(newPhotos)
            matchToVisits(newPhotos)
        }
        PhotoImportResult(newPhotos.size, duplicates, skipped)
    }

    private suspend fun matchToVisits(photos: List<PhotoEntity>) {
        val bounds = photos.mapNotNull { it.t }.let { times ->
            if (times.isEmpty()) return
            val min = times.min() - 24 * 60 * 60_000L
            val max = times.max() + 24 * 60 * 60_000L
            min to max
        }
        val visits = repository.visitsBetween(bounds.first, bounds.second)
        val stays = visits.map {
            Stay(it.id, it.start, it.end, it.durationMs, it.lat, it.lng, it.samples, it.confidence)
        }
        val match = matchPhotos(stays, photos.map { PhotoCandidate(it.id, it.t, it.lat, it.lng) })
        match.placed.forEach { (visitId, ids) ->
            val placeId = visits.firstOrNull { it.id == visitId }?.placeId
            repository.attachPhotos(ids, visitId, placeId)
        }
    }

    private fun displayMeta(uri: Uri): Triple<String, Long, Long>? {
        val cursor = context.contentResolver.query(uri, null, null, null, null) ?: return null
        return cursor.use {
            if (!it.moveToFirst()) return null
            val nameIdx = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIdx = it.getColumnIndex(OpenableColumns.SIZE)
            val name = if (nameIdx >= 0) it.getString(nameIdx) else "photo-${System.currentTimeMillis()}"
            val size = if (sizeIdx >= 0) it.getLong(sizeIdx) else 0L
            val modified = uri.toString().hashCode().toLong()
            Triple(name, size, modified)
        }
    }

    private fun readTime(value: String?): Long? {
        if (value.isNullOrBlank()) return null
        val formats = listOf("yyyy:MM:dd HH:mm:ss", "yyyy-MM-dd HH:mm:ss")
        for (format in formats) {
            runCatching { SimpleDateFormat(format, Locale.US).parse(value)?.time }.getOrNull()?.let { return it }
        }
        return null
    }
}
