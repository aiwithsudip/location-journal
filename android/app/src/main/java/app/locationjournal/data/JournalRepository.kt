package app.locationjournal.data

import androidx.room.withTransaction
import app.locationjournal.domain.DistanceUnit
import app.locationjournal.domain.GeoPoint
import app.locationjournal.domain.StayConfig
import app.locationjournal.domain.ThemeMode
import app.locationjournal.domain.dayBounds
import app.locationjournal.domain.dayKey
import app.locationjournal.domain.detectStays
import app.locationjournal.domain.haversineMeters
import app.locationjournal.domain.hourKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class JournalRepository @Inject constructor(
    private val database: JournalDatabase,
    private val preferences: AppPreferences,
) {
    private val pointDao = database.pointDao()
    private val placeDao = database.placeDao()
    private val visitDao = database.visitDao()
    private val photoDao = database.photoDao()
    private val entryDao = database.entryDao()
    private val reelDao = database.reelDao()

    val settings: Flow<AppSettings> = preferences.settings

    fun observePoints(from: Long, to: Long): Flow<List<PointEntity>> = pointDao.observeBetween(from, to)
    fun observeVisits(from: Long, to: Long): Flow<List<VisitEntity>> = visitDao.observeBetween(from, to)
    fun observeRecentPoints(limit: Int): Flow<List<PointEntity>> = pointDao.observeRecent(limit)
    fun observeRecentVisits(limit: Int): Flow<List<VisitEntity>> = visitDao.observeRecent(limit)
    fun observePlaces(): Flow<List<PlaceEntity>> = placeDao.observeAll()
    fun observePhotos(): Flow<List<PhotoEntity>> = photoDao.observeAll()
    fun observeEntries(): Flow<List<JournalEntryEntity>> = entryDao.observeAll()
    fun observeReelStates(): Flow<List<ReelStateEntity>> = reelDao.observeStates()
    fun observeReelComments(reelId: String): Flow<List<ReelCommentEntity>> = reelDao.observeComments(reelId)

    suspend fun pointsForDay(day: String): List<PointEntity> {
        val bounds = dayBounds(day)
        return pointDao.between(bounds.first, bounds.last)
    }

    suspend fun visitsForDay(day: String): List<VisitEntity> {
        val bounds = dayBounds(day)
        return visitDao.between(bounds.first, bounds.last)
    }

    suspend fun saveFix(point: GeoPoint, replaceHourly: Boolean) {
        val current = preferences.settings.first()
        if (current.excludeStart != null && current.excludeEnd != null &&
            point.t in current.excludeStart!!..current.excludeEnd!!
        ) return

        val entity = PointEntity(
            id = point.id,
            t = point.t,
            lat = point.lat,
            lng = point.lng,
            accuracy = point.accuracy,
            altitude = point.altitude,
            speed = point.speed,
            bearing = point.bearing,
            kind = point.kind,
            hour = point.hour ?: hourKey(point.t),
        )
        val hourly = PointEntity(
            id = "hourly:${hourKey(point.t)}",
            t = point.t,
            lat = point.lat,
            lng = point.lng,
            accuracy = point.accuracy,
            altitude = point.altitude,
            speed = point.speed,
            bearing = point.bearing,
            kind = "hourly",
            hour = hourKey(point.t),
        )
        database.withTransaction {
            pointDao.upsertAll(listOfNotNull(entity, hourly.takeIf { replaceHourly || point.kind == "hourly" }))
        }
        val bounds = dayBounds(dayKey(point.t))
        rebuildIntelligence(bounds.first, bounds.last)
    }

    suspend fun rebuildIntelligence(from: Long, to: Long) {
        val current = preferences.settings.first()
        val config = StayConfig(
            radiusMeters = current.stayRadiusMeters.toDouble(),
            minDurationMs = current.stayMinMinutes * 60_000L,
            maxGapMs = current.stayMaxGapMinutes * 60_000L,
            maxAccuracyMeters = current.maxAccuracyMeters.toFloat(),
        )
        database.withTransaction {
            val points = pointDao.between(from, to).map {
                GeoPoint(
                    id = it.id,
                    t = it.t,
                    lat = it.lat,
                    lng = it.lng,
                    accuracy = it.accuracy,
                    kind = it.kind,
                    hour = it.hour,
                )
            }
            val stays = detectStays(points, config)
            val existing = visitDao.between(from, to).associateBy { it.id }
            val places = placeDao.all().filter { it.mergedInto == null }.toMutableList()
            val newPlaces = mutableListOf<PlaceEntity>()
            val newVisits = mutableListOf<VisitEntity>()

            for (stay in stays) {
                val place = places.firstOrNull {
                    haversineMeters(
                        app.locationjournal.domain.LatLng(it.lat, it.lng),
                        app.locationjournal.domain.LatLng(stay.lat, stay.lng),
                    ) <= config.radiusMeters
                } ?: PlaceEntity(
                    id = "place-${stay.lat.round4()}-${stay.lng.round4()}",
                    lat = stay.lat,
                    lng = stay.lng,
                    name = "Place near ${stay.lat.round4()}, ${stay.lng.round4()}",
                    firstSeen = stay.start,
                    lastSeen = stay.end,
                ).also { created ->
                    places += created
                    newPlaces += created
                }
                val old = existing[stay.id]
                newVisits += VisitEntity(
                    id = stay.id,
                    placeId = place.id,
                    day = app.locationjournal.domain.dayKey(stay.start),
                    start = stay.start,
                    end = stay.end,
                    durationMs = stay.durationMs,
                    lat = stay.lat,
                    lng = stay.lng,
                    samples = stay.samples,
                    confidence = stay.confidence,
                    title = old?.title,
                    note = old?.note,
                    category = old?.category ?: place.category.takeIf { it != "Other" },
                )
            }
            visitDao.deleteRange(from, to)
            if (newPlaces.isNotEmpty()) placeDao.upsertAll(newPlaces)
            if (newVisits.isNotEmpty()) visitDao.upsertAll(newVisits)
            recalculatePlaceStats()
        }
    }

    suspend fun rebuildAllIntelligence() {
        val first = pointDao.recent(Int.MAX_VALUE).minByOrNull { it.t }?.t ?: return
        rebuildIntelligence(first - 1L, System.currentTimeMillis() + 60_000L)
    }

    suspend fun updatePlace(place: PlaceEntity) = placeDao.upsert(place)

    suspend fun mergePlaces(sourceId: String, targetId: String) {
        database.withTransaction {
            val source = placeDao.byId(sourceId) ?: return@withTransaction
            val target = placeDao.byId(targetId) ?: return@withTransaction
            val visits = visitDao.byPlace(sourceId)
            visits.forEach { visitDao.reassign(it.id, targetId, it.category ?: target.category) }
            placeDao.upsert(
                target.copy(
                    visitCount = target.visitCount + source.visitCount,
                    totalDurationMs = target.totalDurationMs + source.totalDurationMs,
                    firstSeen = minOf(target.firstSeen, source.firstSeen),
                    lastSeen = maxOf(target.lastSeen, source.lastSeen),
                )
            )
            placeDao.upsert(source.copy(mergedInto = targetId))
            recalculatePlaceStats()
        }
    }

    suspend fun setVisitTitle(id: String, title: String?) = visitDao.setTitle(id, title)
    suspend fun setVisitNote(id: String, note: String?) = visitDao.setNote(id, note)

    suspend fun upsertPhotos(photos: List<PhotoEntity>) = photoDao.upsertAll(photos)

    suspend fun visitsBetween(from: Long, to: Long): List<VisitEntity> = visitDao.between(from, to)

    suspend fun attachPhotos(ids: List<String>, visitId: String?, placeId: String?) =
        photoDao.attach(ids, visitId, placeId)

    suspend fun updatePhoto(id: String, caption: String?, label: String?, favorite: Boolean) =
        photoDao.updateMetadata(id, caption, label, favorite)

    suspend fun addEntry(entry: JournalEntryEntity) = entryDao.upsert(entry)
    suspend fun deleteEntry(id: String) = entryDao.delete(id)

    suspend fun upsertReelState(state: ReelStateEntity) = reelDao.upsertState(state)
    suspend fun addReelComment(comment: ReelCommentEntity) = reelDao.upsertComment(comment)
    suspend fun deleteReelComment(id: String) = reelDao.deleteComment(id)

    suspend fun importLegacy(data: LegacyData) {
        database.withTransaction {
            pointDao.insertAll(data.points)
            photoDao.upsertAll(data.photos)
            reelDao.upsertState(
                ReelStateEntity(id = "__prefs", index = data.settings["reelIndex"]?.toIntOrNull() ?: 0,
                    muted = data.settings["reelMuted"]?.toBooleanStrictOrNull() ?: true)
            )
            data.reelStates.forEach { reelDao.upsertState(it) }
            data.reelComments.forEach { reelDao.upsertComment(it) }
        }
        preferences.update { current ->
            current.copy(
                units = runCatching { DistanceUnit.valueOf(data.settings["units"] ?: "KM") }.getOrDefault(current.units),
                theme = runCatching { ThemeMode.valueOf(data.settings["theme"] ?: "SYSTEM") }.getOrDefault(current.theme),
            )
        }
        rebuildAllIntelligence()
    }

    suspend fun importBackup(data: LegacyData, replace: Boolean) {
        database.withTransaction {
            if (replace) {
                pointDao.deleteAll()
                photoDao.deleteAll()
                visitDao.deleteAll()
                placeDao.deleteAll()
                entryDao.deleteAll()
            }
            pointDao.upsertAll(data.points)
            photoDao.upsertAll(data.photos)
        }
        if (data.settings.isNotEmpty()) {
            preferences.replaceAll(
                AppSettings(
                    units = runCatching { DistanceUnit.valueOf(data.settings["units"] ?: "KM") }.getOrDefault(DistanceUnit.KM),
                    theme = runCatching { ThemeMode.valueOf(data.settings["theme"] ?: "SYSTEM") }.getOrDefault(ThemeMode.SYSTEM),
                    onboardingComplete = true,
                    legacyImportComplete = true,
                )
            )
        }
        rebuildAllIntelligence()
    }

    suspend fun deleteDay(day: String) {
        val bounds = dayBounds(day)
        database.withTransaction {
            pointDao.deleteRange(bounds.first, bounds.last)
            visitDao.deleteRange(bounds.first, bounds.last)
        }
    }

    suspend fun deletePoint(id: String) = pointDao.deleteByIds(listOf(id))
    suspend fun deleteVisit(id: String) = visitDao.delete(id)
    suspend fun clearAllData() {
        database.withTransaction {
            pointDao.deleteAll()
            visitDao.deleteAll()
            placeDao.deleteAll()
            photoDao.deleteAll()
            entryDao.deleteAll()
            reelDao.deleteComments()
            reelDao.deleteStates()
        }
    }

    suspend fun snapshot(): BackupSnapshot = BackupSnapshot(
        points = pointDao.recent(Int.MAX_VALUE),
        photos = photoDao.observeAll().first(),
        places = placeDao.all(),
        visits = visitDao.allVisits(),
        entries = entryDao.observeAll().first(),
        reelStates = reelDao.observeStates().first(),
        reelComments = reelDao.observeAllComments().first(),
        settings = preferences.settings.first(),
    )

    suspend fun countPoints(): Int = pointDao.count()

    private suspend fun recalculatePlaceStats() {
        val visits = visitDao.allVisits()
        val updated = placeDao.all().map { place ->
            if (place.mergedInto != null) place else {
                val own = visits.filter { it.placeId == place.id }
                place.copy(
                    visitCount = own.size,
                    totalDurationMs = own.sumOf { it.durationMs },
                    firstSeen = own.minOfOrNull { it.start } ?: place.firstSeen,
                    lastSeen = own.maxOfOrNull { it.end } ?: place.lastSeen,
                )
            }
        }
        if (updated.isNotEmpty()) placeDao.upsertAll(updated)
    }
}

private fun Double.round4(): String = String.format(java.util.Locale.US, "%.4f", this)

data class BackupSnapshot(
    val points: List<PointEntity>,
    val photos: List<PhotoEntity>,
    val places: List<PlaceEntity>,
    val visits: List<VisitEntity>,
    val entries: List<JournalEntryEntity>,
    val reelStates: List<ReelStateEntity>,
    val reelComments: List<ReelCommentEntity>,
    val settings: AppSettings,
)
