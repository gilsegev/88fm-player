package com.example.player88

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

class PlaybackDataRepository(private val context: Context) {

    private fun positionKey(episodeId: String) = longPreferencesKey("${episodeId}_position")
    private fun playedKey(episodeId: String) = booleanPreferencesKey("${episodeId}_played")
    private fun likedKey(episodeId: String) = booleanPreferencesKey("${episodeId}_liked")
    private fun dislikedKey(episodeId: String) = booleanPreferencesKey("${episodeId}_disliked")

    suspend fun savePlaybackPosition(episodeId: String, positionMs: Long) {
        context.dataStore.edit { preferences ->
            preferences[positionKey(episodeId)] = positionMs
        }
    }

    suspend fun markAsPlayed(episodeId: String) {
        context.dataStore.edit { preferences ->
            preferences[playedKey(episodeId)] = true
            preferences[positionKey(episodeId)] = 0L
        }
    }

    suspend fun toggleLiked(episodeId: String) {
        context.dataStore.edit { preferences ->
            val current = preferences[likedKey(episodeId)] ?: false
            preferences[likedKey(episodeId)] = !current
        }
    }

    suspend fun markDisliked(episodeId: String) {
        context.dataStore.edit { preferences ->
            preferences[dislikedKey(episodeId)] = true
        }
    }

    suspend fun clearAllCuration() {
        context.dataStore.edit { preferences ->
            val keysToRemove = preferences.asMap().keys.filter { 
                it.name.endsWith("_liked") || it.name.endsWith("_disliked") 
            }
            keysToRemove.forEach { preferences.remove(it) }
        }
    }

    fun getPlaybackPosition(episodeId: String): Flow<Long> {
        return context.dataStore.data
            .catch { exception ->
                if (exception is IOException) {
                    emit(emptyPreferences())
                } else {
                    throw exception
                }
            }
            .map { preferences ->
                preferences[positionKey(episodeId)] ?: 0L
            }
    }

    fun isPlayed(episodeId: String): Flow<Boolean> {
        return context.dataStore.data
            .catch { exception ->
                if (exception is IOException) {
                    emit(emptyPreferences())
                } else {
                    throw exception
                }
            }
            .map { preferences ->
                preferences[playedKey(episodeId)] ?: false
            }
    }

    fun isLiked(episodeId: String): Flow<Boolean> {
        return context.dataStore.data
            .catch { exception ->
                if (exception is IOException) {
                    emit(emptyPreferences())
                } else {
                    throw exception
                }
            }
            .map { preferences ->
                preferences[likedKey(episodeId)] ?: false
            }
    }

    fun getDislikedIds(): Flow<Set<String>> {
        return context.dataStore.data
            .catch { exception ->
                if (exception is IOException) {
                    emit(emptyPreferences())
                } else {
                    throw exception
                }
            }
            .map { preferences ->
                preferences.asMap().keys
                    .filter { it.name.endsWith("_disliked") }
                    .filter { preferences[booleanPreferencesKey(it.name)] == true }
                    .map { it.name.removeSuffix("_disliked") }
                    .toSet()
            }
    }

    fun getAllPlayedStatuses(): Flow<Map<String, Boolean>> {
        return context.dataStore.data
            .catch { exception ->
                if (exception is IOException) {
                    emit(emptyPreferences())
                } else {
                    throw exception
                }
            }
            .map { preferences ->
                preferences.asMap()
                    .filterKeys { it.name.endsWith("_played") }
                    .mapKeys { it.key.name.removeSuffix("_played") }
                    .mapValues { it.value as Boolean }
            }
    }

    fun getAllLikedStatuses(): Flow<Map<String, Boolean>> {
        return context.dataStore.data
            .catch { exception ->
                if (exception is IOException) {
                    emit(emptyPreferences())
                } else {
                    throw exception
                }
            }
            .map { preferences ->
                preferences.asMap()
                    .filterKeys { it.name.endsWith("_liked") }
                    .mapKeys { it.key.name.removeSuffix("_liked") }
                    .mapValues { it.value as Boolean }
            }
    }
}
