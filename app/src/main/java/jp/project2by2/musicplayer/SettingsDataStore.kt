package jp.project2by2.musicplayer

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore(name = "settings")

object SettingsDataStore {
    private val soundFontNameKey = stringPreferencesKey("sound_font_name")

    private val effectsEnabledKey = booleanPreferencesKey("effects_enabled")
    private val reverbStrengthKey = floatPreferencesKey("reverb_strength")

    private val loopEnabledKey = booleanPreferencesKey("loop_enabled")
    private val loopModeKey = intPreferencesKey("loop_mode")
    private val shuffleEnabledKey = booleanPreferencesKey("shuffle_enabled")

    private val demoFilesLoadedKey = booleanPreferencesKey("demo_files_loaded")

    fun soundFontNameFlow(context: Context): Flow<String?> {
        return context.settingsDataStore.data.map { prefs ->
            prefs[soundFontNameKey]
        }
    }

    fun effectsEnabledFlow(context: Context): Flow<Boolean> =
        context.settingsDataStore.data
            .map { it[effectsEnabledKey] ?: false }
            .distinctUntilChanged()

    fun reverbStrengthFlow(context: Context): Flow<Float> =
        context.settingsDataStore.data
            .map { it[reverbStrengthKey] ?: 1f }
            .distinctUntilChanged()

    fun loopEnabledFlow(context: Context): Flow<Boolean> =
        context.settingsDataStore.data
            .map { it[loopEnabledKey] ?: false }
            .distinctUntilChanged()

    fun loopModeFlow(context: Context): Flow<Int> =
        context.settingsDataStore.data
            .map { it[loopModeKey] ?: 0 }
            .distinctUntilChanged()

    fun shuffleEnabledFlow(context: Context): Flow<Boolean> =
        context.settingsDataStore.data
            .map { it[shuffleEnabledKey] ?: false }
            .distinctUntilChanged()

    fun demoFilesLoadedFlow(context: Context): Flow<Boolean> =
        context.settingsDataStore.data
            .map { it[demoFilesLoadedKey] ?: false }
            .distinctUntilChanged()

    suspend fun setSoundFontName(context: Context, name: String) {
        context.settingsDataStore.edit { prefs ->
            prefs[soundFontNameKey] = name
        }
    }

    suspend fun setEffectsEnabled(context: Context, enabled: Boolean) {
        context.settingsDataStore.edit { it[effectsEnabledKey] = enabled }
    }

    suspend fun setReverbStrength(context: Context, value: Float) {
        context.settingsDataStore.edit { it[reverbStrengthKey] = value }
    }

    suspend fun setLoopEnabled(context: Context, enabled: Boolean) {
        context.settingsDataStore.edit { it[loopEnabledKey] = enabled }
    }

    suspend fun setLoopMode(context: Context, loopMode: Int) {
        context.settingsDataStore.edit { it[loopModeKey] = loopMode }
    }

    suspend fun setShuffleEnabled(context: Context, enabled: Boolean) {
        context.settingsDataStore.edit { it[shuffleEnabledKey] = enabled }
    }

    suspend fun setDemoFilesLoaded(context: Context, loaded: Boolean) {
        context.settingsDataStore.edit { it[demoFilesLoadedKey] = loaded }
    }
}
