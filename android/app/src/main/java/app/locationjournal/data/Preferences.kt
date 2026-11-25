package app.locationjournal.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.locationjournal.domain.DistanceUnit
import app.locationjournal.domain.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.appDataStore: DataStore<Preferences> by preferencesDataStore(name = "journal_settings")

data class AppSettings(
    val units: DistanceUnit = DistanceUnit.KM,
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val sampleIntervalSeconds: Long = 20L,
    val stayRadiusMeters: Int = 200,
    val stayMinMinutes: Int = 30,
    val stayMaxGapMinutes: Int = 70,
    val maxAccuracyMeters: Int = 250,
    val showStayRings: Boolean = true,
    val followOnStart: Boolean = true,
    val autoPhoto: Boolean = false,
    val excludeStart: Long? = null,
    val excludeEnd: Long? = null,
    val onboardingComplete: Boolean = false,
    val legacyImportComplete: Boolean = false,
    val nightStartHour: Int = 20,
    val nightEndHour: Int = 6,
)

class AppPreferences(private val context: Context) {
    private object Keys {
        val UNITS = stringPreferencesKey("units")
        val THEME = stringPreferencesKey("theme")
        val SAMPLE_INTERVAL = longPreferencesKey("sample_interval_seconds")
        val STAY_RADIUS = intPreferencesKey("stay_radius_meters")
        val STAY_MIN = intPreferencesKey("stay_min_minutes")
        val STAY_GAP = intPreferencesKey("stay_gap_minutes")
        val MAX_ACCURACY = intPreferencesKey("max_accuracy_meters")
        val SHOW_RINGS = booleanPreferencesKey("show_stay_rings")
        val FOLLOW = booleanPreferencesKey("follow_on_start")
        val AUTO_PHOTO = booleanPreferencesKey("auto_photo")
        val EXCLUDE_START = longPreferencesKey("exclude_start")
        val EXCLUDE_END = longPreferencesKey("exclude_end")
        val ONBOARDING = booleanPreferencesKey("onboarding_complete")
        val LEGACY_IMPORT = booleanPreferencesKey("legacy_import_complete")
        val NIGHT_START = intPreferencesKey("night_start_hour")
        val NIGHT_END = intPreferencesKey("night_end_hour")
    }

    val settings: Flow<AppSettings> = context.appDataStore.data
        .catch { error -> if (error is IOException) emit(emptyPreferences()) else throw error }
        .map { prefs ->
            AppSettings(
                units = runCatching { DistanceUnit.valueOf(prefs[Keys.UNITS] ?: "KM") }.getOrDefault(DistanceUnit.KM),
                theme = runCatching { ThemeMode.valueOf(prefs[Keys.THEME] ?: "SYSTEM") }.getOrDefault(ThemeMode.SYSTEM),
                sampleIntervalSeconds = (prefs[Keys.SAMPLE_INTERVAL] ?: 20L).coerceIn(5L, 300L),
                stayRadiusMeters = (prefs[Keys.STAY_RADIUS] ?: 200).coerceIn(50, 1000),
                stayMinMinutes = (prefs[Keys.STAY_MIN] ?: 30).coerceIn(5, 180),
                stayMaxGapMinutes = (prefs[Keys.STAY_GAP] ?: 70).coerceIn(10, 240),
                maxAccuracyMeters = (prefs[Keys.MAX_ACCURACY] ?: 250).coerceIn(50, 1000),
                showStayRings = prefs[Keys.SHOW_RINGS] ?: true,
                followOnStart = prefs[Keys.FOLLOW] ?: true,
                autoPhoto = prefs[Keys.AUTO_PHOTO] ?: false,
                excludeStart = prefs[Keys.EXCLUDE_START],
                excludeEnd = prefs[Keys.EXCLUDE_END],
                onboardingComplete = prefs[Keys.ONBOARDING] ?: false,
                legacyImportComplete = prefs[Keys.LEGACY_IMPORT] ?: false,
                nightStartHour = (prefs[Keys.NIGHT_START] ?: 20).coerceIn(0, 23),
                nightEndHour = (prefs[Keys.NIGHT_END] ?: 6).coerceIn(0, 23),
            )
        }

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        context.appDataStore.edit { prefs ->
            val current = AppSettings(
                units = runCatching { DistanceUnit.valueOf(prefs[Keys.UNITS] ?: "KM") }.getOrDefault(DistanceUnit.KM),
                theme = runCatching { ThemeMode.valueOf(prefs[Keys.THEME] ?: "SYSTEM") }.getOrDefault(ThemeMode.SYSTEM),
                sampleIntervalSeconds = prefs[Keys.SAMPLE_INTERVAL] ?: 20L,
                stayRadiusMeters = prefs[Keys.STAY_RADIUS] ?: 200,
                stayMinMinutes = prefs[Keys.STAY_MIN] ?: 30,
                stayMaxGapMinutes = prefs[Keys.STAY_GAP] ?: 70,
                maxAccuracyMeters = prefs[Keys.MAX_ACCURACY] ?: 250,
                showStayRings = prefs[Keys.SHOW_RINGS] ?: true,
                followOnStart = prefs[Keys.FOLLOW] ?: true,
                autoPhoto = prefs[Keys.AUTO_PHOTO] ?: false,
                excludeStart = prefs[Keys.EXCLUDE_START],
                excludeEnd = prefs[Keys.EXCLUDE_END],
                onboardingComplete = prefs[Keys.ONBOARDING] ?: false,
                legacyImportComplete = prefs[Keys.LEGACY_IMPORT] ?: false,
                nightStartHour = prefs[Keys.NIGHT_START] ?: 20,
                nightEndHour = prefs[Keys.NIGHT_END] ?: 6,
            )
            val next = transform(current)
            prefs[Keys.UNITS] = next.units.name
            prefs[Keys.THEME] = next.theme.name
            prefs[Keys.SAMPLE_INTERVAL] = next.sampleIntervalSeconds
            prefs[Keys.STAY_RADIUS] = next.stayRadiusMeters
            prefs[Keys.STAY_MIN] = next.stayMinMinutes
            prefs[Keys.STAY_GAP] = next.stayMaxGapMinutes
            prefs[Keys.MAX_ACCURACY] = next.maxAccuracyMeters
            prefs[Keys.SHOW_RINGS] = next.showStayRings
            prefs[Keys.FOLLOW] = next.followOnStart
            prefs[Keys.AUTO_PHOTO] = next.autoPhoto
            if (next.excludeStart == null) prefs.remove(Keys.EXCLUDE_START) else prefs[Keys.EXCLUDE_START] = next.excludeStart!!
            if (next.excludeEnd == null) prefs.remove(Keys.EXCLUDE_END) else prefs[Keys.EXCLUDE_END] = next.excludeEnd!!
            prefs[Keys.ONBOARDING] = next.onboardingComplete
            prefs[Keys.LEGACY_IMPORT] = next.legacyImportComplete
            prefs[Keys.NIGHT_START] = next.nightStartHour
            prefs[Keys.NIGHT_END] = next.nightEndHour
        }
    }

    suspend fun replaceAll(settings: AppSettings) {
        context.appDataStore.edit { prefs -> prefs.clear() }
        update { settings }
    }

    suspend fun markOnboardingComplete() = update { it.copy(onboardingComplete = true) }
    suspend fun markLegacyImportComplete() = update { it.copy(legacyImportComplete = true) }
}
