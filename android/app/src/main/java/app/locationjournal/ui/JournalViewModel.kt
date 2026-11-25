package app.locationjournal.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.locationjournal.data.AppPreferences
import app.locationjournal.data.AppSettings
import app.locationjournal.data.BackupManager
import app.locationjournal.data.JournalEntryEntity
import app.locationjournal.data.JournalRepository
import app.locationjournal.data.LegacyImporter
import app.locationjournal.data.PhotoImporter
import app.locationjournal.data.ReelMediaStore
import app.locationjournal.data.ReelMedia
import app.locationjournal.domain.dayBounds
import app.locationjournal.domain.dayKey
import app.locationjournal.domain.summarize
import app.locationjournal.tracking.TorchController
import app.locationjournal.tracking.TrackingController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class JournalViewModel @Inject constructor(
    val repository: JournalRepository,
    val tracking: TrackingController,
    val preferences: AppPreferences,
    val backup: BackupManager,
    val photoImporter: PhotoImporter,
    val torch: TorchController,
    val legacyImporter: LegacyImporter,
    val reelMediaStore: ReelMediaStore,
) : ViewModel() {
    private val _selectedDay = MutableStateFlow(dayKey(System.currentTimeMillis()))
    val selectedDay: StateFlow<String> = _selectedDay

    private val _message = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val message = _message.asSharedFlow()

    private val _reelMedia = MutableStateFlow<Map<String, ReelMedia>>(emptyMap())
    val reelMedia: StateFlow<Map<String, ReelMedia>> = _reelMedia

    val settings = repository.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())
    val trackingState = tracking.state.stateIn(viewModelScope, SharingStarted.Eagerly, tracking.current())
    val torchState = torch.status.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), torch.status.value)

    private val bounds = _selectedDay.map { dayBounds(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), dayBounds(_selectedDay.value))

    val dayPoints = bounds.flatMapLatest { repository.observePoints(it.first, it.last) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val dayVisits = bounds.flatMapLatest { range ->
        repository.observeVisits(range.first, range.last)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val summary = combine(dayPoints, dayVisits) { points, visits ->
        summarize(
            points.map { app.locationjournal.domain.GeoPoint(it.id, it.t, it.lat, it.lng, it.accuracy, it.altitude, it.speed, it.bearing, it.kind, it.hour) },
            visits.map { app.locationjournal.domain.Stay(it.id, it.start, it.end, it.durationMs, it.lat, it.lng, it.samples, it.confidence) },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), summarize(emptyList(), emptyList()))

    val recentPoints = repository.observeRecentPoints(120)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val recentVisits = repository.observeRecentVisits(60)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val places = repository.observePlaces()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val photos = repository.observePhotos()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val entries = repository.observeEntries()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val reelStates = repository.observeReelStates()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        viewModelScope.launch {
            runCatching {
                torch.start()
                legacyImporter.importIfNeeded()
                _reelMedia.value = reelMediaStore.all()
            }.onFailure { _message.tryEmit("Some saved data could not be migrated yet: ${it.message ?: "unknown error"}") }
        }
    }

    fun reelComments(reelId: String) = repository.observeReelComments(reelId)

    fun selectDay(day: String) { _selectedDay.value = day }

    fun startTracking() = tracking.start()
    fun pauseTracking() = tracking.pause()
    fun resumeTracking() = tracking.resume()
    fun stopTracking() = tracking.stop()
    fun saveFix() = tracking.captureFix(true)

    fun updateSettings(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch { preferences.update(transform) }
    }

    fun completeOnboarding() {
        viewModelScope.launch { preferences.markOnboardingComplete() }
    }

    fun toggleTorch() {
        viewModelScope.launch {
            val result = torch.setEnabled(!torchState.value.on)
            if (result == "denied") _message.emit("Camera permission is needed for the flashlight.")
            else if (result == "busy") _message.emit("The flashlight is busy. Close the camera and try again.")
        }
    }

    fun importPhotos(uris: List<Uri>) {
        viewModelScope.launch {
            val result = runCatching { photoImporter.import(uris) }.getOrElse {
                _message.emit("Photos could not be imported: ${it.message ?: "unknown error"}")
                return@launch
            }
            _message.emit("Imported ${result.imported} photos; ${result.duplicates} duplicates skipped.")
        }
    }

    fun addEntry(title: String, body: String, category: String?, placeId: String?, visitId: String?) {
        if (title.isBlank() && body.isBlank()) return
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            repository.addEntry(
                JournalEntryEntity(
                    id = "entry:$now:${UUID.randomUUID()}",
                    title = title.ifBlank { "Journal entry" },
                    body = body,
                    category = category?.takeIf { it.isNotBlank() },
                    time = now,
                    placeId = placeId,
                    visitId = visitId,
                    createdAt = now,
                    updatedAt = now,
                )
            )
            _message.emit("Journal entry saved.")
        }
    }

    fun deleteEntry(id: String) = viewModelScope.launch { repository.deleteEntry(id) }

    fun setVisitNote(id: String, note: String) = viewModelScope.launch { repository.setVisitNote(id, note) }
    fun setVisitTitle(id: String, title: String) = viewModelScope.launch { repository.setVisitTitle(id, title) }
    fun renamePlace(id: String, name: String, category: String) = viewModelScope.launch {
        val place = repository.observePlaces().first().firstOrNull { it.id == id } ?: return@launch
        repository.updatePlace(place.copy(name = name.ifBlank { place.name }, category = category))
    }
    fun mergePlaces(sourceId: String, targetId: String) = viewModelScope.launch {
        repository.mergePlaces(sourceId, targetId)
        _message.emit("Duplicate places merged.")
    }
    fun attachPhotos(ids: List<String>, visitId: String?, placeId: String?) = viewModelScope.launch {
        repository.attachPhotos(ids, visitId, placeId)
        _message.emit("Photos attached to the visit.")
    }

    fun exportJson(uri: Uri) = runCatchingExport("JSON backup") { backup.exportJson(uri) }
    fun exportGpx(uri: Uri) = runCatchingExport("GPX") { backup.exportGpx(uri) }
    fun exportCsv(uri: Uri) = runCatchingExport("CSV") { backup.exportCsv(uri) }

    fun importBackup(uri: Uri, replace: Boolean) {
        viewModelScope.launch {
            val result = runCatching { backup.importJson(uri, replace) }.getOrElse {
                "Import failed: ${it.message ?: "unknown error"}"
            }
            _message.emit(result)
        }
    }

    fun deleteDay(day: String) = viewModelScope.launch {
        repository.deleteDay(day)
        _message.emit("Deleted data for $day.")
    }

    fun clearAllData() = viewModelScope.launch {
        repository.clearAllData()
        tracking.stop()
        _message.emit("All app data was deleted.")
    }

    fun toggleLike(reelId: String) = viewModelScope.launch {
        val current = repository.observeReelStates().first().firstOrNull { it.id == reelId }
        val base = REEL_STATE_DEFAULTS[reelId]
        val liked = !(current?.liked ?: false)
        repository.upsertReelState(
            app.locationjournal.data.ReelStateEntity(
                id = reelId,
                liked = liked,
                saved = current?.saved ?: false,
                likes = (current?.likes ?: base?.likes ?: 0) + if (liked) 1 else -1,
                views = current?.views ?: base?.views ?: 0,
                shares = current?.shares ?: base?.shares ?: 0,
                reshares = current?.reshares ?: base?.reshares ?: 0,
                index = current?.index ?: 0,
                muted = current?.muted ?: true,
            )
        )
    }

    fun toggleSave(reelId: String) = viewModelScope.launch {
        val current = repository.observeReelStates().first().firstOrNull { it.id == reelId }
        val base = REEL_STATE_DEFAULTS[reelId]
        val saved = !(current?.saved ?: false)
        repository.upsertReelState(
            app.locationjournal.data.ReelStateEntity(
                id = reelId,
                liked = current?.liked ?: false,
                saved = saved,
                likes = current?.likes ?: base?.likes ?: 0,
                views = current?.views ?: base?.views ?: 0,
                shares = current?.shares ?: base?.shares ?: 0,
                reshares = current?.reshares ?: base?.reshares ?: 0,
                index = current?.index ?: 0,
                muted = current?.muted ?: true,
            )
        )
    }

    fun recordView(reelId: String) = viewModelScope.launch {
        val current = repository.observeReelStates().first().firstOrNull { it.id == reelId }
        val base = REEL_STATE_DEFAULTS[reelId]
        repository.upsertReelState(
            (current ?: app.locationjournal.data.ReelStateEntity(id = reelId)).copy(
                views = (current?.views ?: base?.views ?: 0) + 1,
            )
        )
    }

    fun shareReel(reelId: String) = viewModelScope.launch {
        val current = repository.observeReelStates().first().firstOrNull { it.id == reelId }
        val base = REEL_STATE_DEFAULTS[reelId]
        repository.upsertReelState(
            (current ?: app.locationjournal.data.ReelStateEntity(id = reelId)).copy(
                shares = (current?.shares ?: base?.shares ?: 0) + 1,
            )
        )
        _message.emit("Shared locally as location-journal://reel/$reelId")
    }

    fun reshare(reelId: String) = viewModelScope.launch {
        val current = repository.observeReelStates().first().firstOrNull { it.id == reelId }
        val base = REEL_STATE_DEFAULTS[reelId]
        repository.upsertReelState(
            (current ?: app.locationjournal.data.ReelStateEntity(id = reelId)).copy(
                reshares = (current?.reshares ?: base?.reshares ?: 0) + 1,
            )
        )
    }

    fun undoReshare(reelId: String) = viewModelScope.launch {
        val current = repository.observeReelStates().first().firstOrNull { it.id == reelId } ?: return@launch
        repository.upsertReelState(current.copy(reshares = (current.reshares - 1).coerceAtLeast(0)))
    }

    fun addReelComment(reelId: String, text: String) {
        val clean = text.trim().take(280)
        if (clean.isBlank()) return
        viewModelScope.launch {
            repository.addReelComment(
                app.locationjournal.data.ReelCommentEntity(
                    id = "comment:$reelId:${System.currentTimeMillis()}:${UUID.randomUUID()}",
                    reelId = reelId,
                    author = "You",
                    text = clean,
                    time = System.currentTimeMillis(),
                    mine = true,
                )
            )
        }
    }

    fun deleteReelComment(id: String) = viewModelScope.launch { repository.deleteReelComment(id) }

    fun setReelPrefs(index: Int, muted: Boolean) = viewModelScope.launch {
        repository.upsertReelState(
            app.locationjournal.data.ReelStateEntity(id = "__prefs", index = index, muted = muted),
        )
    }

    private fun runCatchingExport(label: String, block: suspend () -> Unit) {
        viewModelScope.launch {
            val error = runCatching { block() }.exceptionOrNull()
            _message.emit(if (error == null) "$label exported." else "$label export failed: ${error.message ?: "unknown error"}")
        }
    }

    companion object {
        val REEL_STATE_DEFAULTS: Map<String, app.locationjournal.data.ReelStateEntity> =
            app.locationjournal.domain.REEL_CATALOG.associate { reel ->
                reel.id to app.locationjournal.data.ReelStateEntity(
                    id = reel.id,
                    likes = reel.likes,
                    views = reel.views,
                    shares = reel.shares,
                    reshares = reel.reshares,
                )
            }
    }
}
