package app.locationjournal.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class TrackingTransitionsTest {
    @Test
    fun transitionsOnlyAllowValidMoves() {
        val t = TrackingTransitions
        assertEquals(TrackingState.RUNNING, t.next(TrackingState.STOPPED, TrackingAction.START))
        assertEquals(TrackingState.PAUSED, t.next(TrackingState.RUNNING, TrackingAction.PAUSE))
        assertEquals(TrackingState.RUNNING, t.next(TrackingState.PAUSED, TrackingAction.RESUME))
        assertEquals(TrackingState.STOPPED, t.next(TrackingState.RUNNING, TrackingAction.STOP))
        assertEquals(TrackingState.PAUSED, t.next(TrackingState.PAUSED, TrackingAction.PAUSE))
        assertEquals(TrackingState.STOPPED, t.next(TrackingState.STOPPED, TrackingAction.PAUSE))
    }
}
