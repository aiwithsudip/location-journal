package app.locationjournal.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.locationjournal.domain.GeoPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class JournalRepositoryTest {
    private lateinit var database: JournalDatabase
    private lateinit var preferences: AppPreferences
    private lateinit var repository: JournalRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        database = Room.inMemoryDatabaseBuilder(context, JournalDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        preferences = AppPreferences(context)
        repository = JournalRepository(database, preferences)
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun rebuildsVisitsAndPlaceStatsWithoutDoubleCounting() = runBlocking {
        val day = "2026-11-25"
        val base = LocalDate.parse(day).atTime(9, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        repeat(4) { index ->
            repository.saveFix(
                GeoPoint("p:$index", base + index * 10 * 60_000L, 27.7172 + index * 0.00001, 85.3240),
                replaceHourly = false,
            )
        }

        val visits = repository.visitsForDay(day)
        assertEquals(1, visits.size)
        assertEquals(4, visits.first().samples)
        assertEquals(30 * 60_000L, visits.first().durationMs)
        val place = repository.observePlaces().first().single()
        assertEquals(1, place.visitCount)
        assertEquals(visits.first().durationMs, place.totalDurationMs)

        repository.rebuildAllIntelligence()
        assertEquals(1, repository.visitsForDay(day).size)
        assertEquals(1, repository.observePlaces().first().single().visitCount)
    }

    @Test
    fun excludedWindowDiscardsFixes() = runBlocking {
        val day = "2026-11-26"
        val base = LocalDate.parse(day).atTime(10, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        preferences.update { it.copy(excludeStart = base, excludeEnd = base + 60 * 60_000L) }
        repository.saveFix(GeoPoint("excluded", base + 5 * 60_000L, 27.7172, 85.3240), false)
        repository.saveFix(GeoPoint("allowed", base + 90 * 60_000L, 27.7172, 85.3240), false)
        assertTrue(repository.observePoints(base - 1L, base + 2 * 60 * 60_000L).first().none { it.id == "excluded" })
        assertTrue(repository.observePoints(base - 1L, base + 2 * 60 * 60_000L).first().any { it.id == "allowed" })
    }
}
