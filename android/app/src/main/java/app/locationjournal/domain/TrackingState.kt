package app.locationjournal.domain

enum class TrackingState { STOPPED, RUNNING, PAUSED }

/** Pure state machine so UI, service, and persisted state can agree on valid transitions. */
object TrackingTransitions {
    fun next(state: TrackingState, action: TrackingAction): TrackingState = when (action) {
        TrackingAction.START -> TrackingState.RUNNING
        TrackingAction.PAUSE -> if (state == TrackingState.RUNNING) TrackingState.PAUSED else state
        TrackingAction.RESUME -> if (state == TrackingState.PAUSED) TrackingState.RUNNING else state
        TrackingAction.STOP -> TrackingState.STOPPED
    }
}

enum class TrackingAction { START, PAUSE, RESUME, STOP }
