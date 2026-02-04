package jp.project2by2.musicplayer

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
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

    fun soundFontNameFlow(context: Context): Flow<String?> {
        return context.settingsDataStore.data.map { prefs ->
            prefs[soundFontNameKey]
        }
    }

    fun effectsEnabledFlow(context: Context): Flow<Boolean> =
        context.settingsDataStore.data
            .map { it[effectsEnabledKey] ?: true }
            .distinctUntilChanged()

    fun reverbStrengthFlow(context: Context): Flow<Float> =
        context.settingsDataStore.data
            .map { it[reverbStrengthKey] ?: 1f }
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
}
