package jp.project2by2.musicplayer

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore(name = "settings")

object SettingsDataStore {
    private val soundFontNameKey = stringPreferencesKey("sound_font_name")

    fun soundFontNameFlow(context: Context): Flow<String?> {
        return context.settingsDataStore.data.map { prefs ->
            prefs[soundFontNameKey]
        }
    }

    suspend fun setSoundFontName(context: Context, name: String) {
        context.settingsDataStore.edit { prefs ->
            prefs[soundFontNameKey] = name
        }
    }
}
