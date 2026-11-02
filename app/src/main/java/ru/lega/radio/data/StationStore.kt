package ru.lega.radio.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import ru.lega.radio.domain.Station
import ru.lega.radio.domain.Stream
import java.io.File

private val Context.radioDataStore: DataStore<Preferences> by preferencesDataStore(name = "radio_prefs")

/**
 * Хранилище: каталог станций — JSON-файл в filesDir (простой кэш),
 * избранное — DataStore.
 *
 * Room тут сознательно не используется: для одного списка станций
 * и набора id это лишний слой. Если появится история прослушиваний
 * и поиск по 10 000 станций — миграция на Room будет локальной,
 * весь остальной код работает через [StationStore].
 */
class StationStore(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true }

    private fun cacheFile(key: String) = File(context.filesDir, "stations_$key.json")

    fun readCache(key: String): List<Station> = runCatching {
        val file = cacheFile(key)
        if (!file.exists()) emptyList()
        else json.decodeFromString<List<Station>>(file.readText())
    }.getOrDefault(emptyList())

    fun writeCache(key: String, stations: List<Station>) {
        runCatching {
            val file = cacheFile(key)
            val tmp = File(context.filesDir, "stations_$key.json.tmp")
            tmp.writeText(json.encodeToString(stations))
            if (file.exists()) file.delete()
            tmp.renameTo(file)
        }
    }

    val favorites: Flow<Set<String>> =
        context.radioDataStore.data.map { it[FAVORITES] ?: emptySet() }

    suspend fun toggleFavorite(stationId: String) {
        context.radioDataStore.edit { prefs ->
            val current = prefs[FAVORITES] ?: emptySet()
            prefs[FAVORITES] = if (stationId in current) current - stationId else current + stationId
        }
    }

    /**
     * Запоминаем станцию, на которой остановились, и играла ли она.
     * Нужно, чтобы при следующем запуске приложение открылось на ней же.
     */
    suspend fun saveLastStation(station: Station, wasPlaying: Boolean) {
        context.radioDataStore.edit { prefs ->
            prefs[LAST_STATION] = json.encodeToString(station)
            prefs[LAST_PLAYING] = wasPlaying
        }
    }

    /** Последняя станция и признак «играла ли»; null — ещё ничего не запускали. */
    suspend fun readLastStation(): Pair<Station, Boolean>? = runCatching {
        val prefs = context.radioDataStore.data.first()
        val encoded = prefs[LAST_STATION] ?: return@runCatching null
        val station = json.decodeFromString<Station>(encoded)
        station to (prefs[LAST_PLAYING] ?: false)
    }.getOrNull()

    /** Станции, добавленные вручную по URL. */
    val userStations: Flow<List<Station>> = context.radioDataStore.data.map { prefs ->
        decodeUserStations(prefs[USER_STATIONS])
    }

    suspend fun addUserStation(name: String, url: String) {
        val trimmedUrl = url.trim()
        val station = Station(
            id = "user:$trimmedUrl",
            name = name.trim().ifBlank { trimmedUrl },
            streams = listOf(
                Stream(
                    url = trimmedUrl,
                    isHls = trimmedUrl.contains(".m3u8", ignoreCase = true),
                ),
            ),
            custom = true,
        )

        context.radioDataStore.edit { prefs ->
            val current = decodeUserStations(prefs[USER_STATIONS])
            if (current.none { it.id == station.id }) {
                prefs[USER_STATIONS] = json.encodeToString(current + station)
            }
        }
    }

    suspend fun removeUserStation(id: String) {
        context.radioDataStore.edit { prefs ->
            val current = decodeUserStations(prefs[USER_STATIONS])
            prefs[USER_STATIONS] = json.encodeToString(current.filterNot { it.id == id })
        }
    }

    private fun decodeUserStations(raw: String?): List<Station> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching { json.decodeFromString<List<Station>>(raw) }.getOrDefault(emptyList())
    }

    private companion object {
        val FAVORITES = stringSetPreferencesKey("favorites")
        val LAST_STATION = stringPreferencesKey("last_station")
        val LAST_PLAYING = booleanPreferencesKey("last_playing")
        val USER_STATIONS = stringPreferencesKey("user_stations")
    }
}
