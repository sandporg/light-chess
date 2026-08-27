package com.thelightphone.chess

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID

class GameStore(
    private val dataStore: DataStore<Preferences>,
) {
    suspend fun loadAll(): List<SavedGame> {
        val snapshot = dataStore.data.first()
        if (needsMigration(snapshot)) {
            dataStore.edit { prefs -> migrateLocked(prefs) }
        }
        return gamesFrom(dataStore.data.first())
            .filter { it.isInProgress }
            .sortedByDescending { it.updatedAtEpochMs }
    }

    suspend fun load(id: String): SavedGame? = loadAll().find { it.id == id }

    suspend fun save(game: SavedGame) {
        dataStore.edit { prefs ->
            migrateLocked(prefs)
            val updated = game.copy(
                id = game.id.ifBlank { newId() },
                updatedAtEpochMs = System.currentTimeMillis(),
            )
            val next = gamesFrom(prefs).filter { it.id != updated.id && it.isInProgress } + updated
            write(prefs, next)
        }
    }

    suspend fun remove(id: String) {
        dataStore.edit { prefs ->
            migrateLocked(prefs)
            write(prefs, gamesFrom(prefs).filter { it.id != id && it.isInProgress })
        }
    }

    private fun needsMigration(prefs: Preferences): Boolean {
        if (prefs.contains(SAVED_GAME)) return true
        val games = decodeList(prefs[SAVED_GAMES]) ?: return false
        return games.any { it.id.isBlank() }
    }

    private fun migrateLocked(prefs: MutablePreferences) {
        val fromList = decodeList(prefs[SAVED_GAMES])
        val fromSingle = prefs[SAVED_GAME]?.let { decodeSingle(it) }
        var games = when {
            !fromList.isNullOrEmpty() -> fromList
            fromSingle != null -> listOf(fromSingle)
            else -> emptyList()
        }
        val needsIds = games.any { it.id.isBlank() }
        if (needsIds) {
            games = games.map { game ->
                if (game.id.isBlank()) game.copy(id = newId()) else game
            }
        }
        games = games.filter { it.isInProgress }
        if (needsIds || prefs.contains(SAVED_GAME)) {
            write(prefs, games)
        }
    }

    private fun gamesFrom(prefs: Preferences): List<SavedGame> =
        decodeList(prefs[SAVED_GAMES]).orEmpty()

    private fun write(prefs: MutablePreferences, games: List<SavedGame>) {
        prefs[SAVED_GAMES] = json.encodeToString(SavedGameList.serializer(), SavedGameList(games))
        prefs.remove(SAVED_GAME)
    }

    private fun decodeList(raw: String?): List<SavedGame>? {
        if (raw.isNullOrBlank()) return null
        return runCatching { json.decodeFromString(SavedGameList.serializer(), raw) }
            .getOrNull()
            ?.games
    }

    private fun decodeSingle(raw: String): SavedGame? =
        runCatching { json.decodeFromString(SavedGame.serializer(), raw) }.getOrNull()

    companion object {
        private val SAVED_GAME = stringPreferencesKey("saved_game")
        private val SAVED_GAMES = stringPreferencesKey("saved_games")
        private val json = Json { ignoreUnknownKeys = true }

        fun newId(): String = UUID.randomUUID().toString()
    }
}

@Serializable
internal data class SavedGameList(
    val games: List<SavedGame> = emptyList(),
)
