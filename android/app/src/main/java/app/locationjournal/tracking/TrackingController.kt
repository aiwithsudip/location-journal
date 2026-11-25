package app.locationjournal.tracking

import android.content.Context
import android.content.Intent
import dagger.hilt.android.qualifiers.ApplicationContext
import app.locationjournal.domain.TrackingAction
import app.locationjournal.domain.TrackingState
import app.locationjournal.domain.TrackingTransitions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TrackingController @Inject constructor(@ApplicationContext private val context: Context) {
    private val prefs = context.getSharedPreferences("tracking_state", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(readPersisted())
    val state: StateFlow<TrackingState> = _state.asStateFlow()

    fun current(): TrackingState = _state.value

    fun start() = dispatch(TrackingAction.START)

    fun pause() = dispatch(TrackingAction.PAUSE)

    fun resume() = dispatch(TrackingAction.RESUME)

    fun stop() = dispatch(TrackingAction.STOP)

    fun captureFix(replaceHourly: Boolean) {
        val intent = Intent(context, JournalTrackingService::class.java).apply {
            action = JournalTrackingService.ACTION_FIX
            putExtra(JournalTrackingService.EXTRA_REPLACE_HOURLY, replaceHourly)
        }
        context.startForegroundService(intent)
    }

    /** Used by the service and boot receiver after Android restarts the process. */
    fun syncFromSystem(state: TrackingState) {
        prefs.edit().putString(KEY_STATE, state.name).apply()
        _state.value = state
    }

    private fun dispatch(action: TrackingAction) {
        val next = TrackingTransitions.next(_state.value, action)
        prefs.edit().putString(KEY_STATE, next.name).apply()
        _state.value = next
        val intent = Intent(context, JournalTrackingService::class.java).apply {
            this.action = when (action) {
                TrackingAction.START -> JournalTrackingService.ACTION_START
                TrackingAction.PAUSE -> JournalTrackingService.ACTION_PAUSE
                TrackingAction.RESUME -> JournalTrackingService.ACTION_RESUME
                TrackingAction.STOP -> JournalTrackingService.ACTION_STOP
            }
        }
        if (next == TrackingState.STOPPED) context.stopService(intent)
        else context.startForegroundService(intent)
    }

    private fun readPersisted(): TrackingState =
        runCatching { TrackingState.valueOf(prefs.getString(KEY_STATE, null) ?: "STOPPED") }
            .getOrDefault(TrackingState.STOPPED)

    private companion object {
        const val KEY_STATE = "state"
    }
}
