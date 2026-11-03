package com.immoral.location.data

import android.content.Context
import androidx.core.content.edit
import com.immoral.location.core.model.FavoritePlace
import com.immoral.location.core.model.GeoPoint
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Persisted list of [FavoritePlace] (收藏地点).
 *
 * Stored as one JSON array in [android.content.SharedPreferences]; the list is small (tens of
 * entries) so a single document is simpler and faster than a database. Every mutation rewrites
 * the document, and reads fall back to an empty list on corrupt data.
 */
class FavoritesStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
    }

    private val _favorites = MutableStateFlow(read())
    /** All saved places, newest first. */
    val favorites: StateFlow<List<FavoritePlace>> = _favorites.asStateFlow()

    /** Saves [point] under [name] and returns the created entry. */
    fun add(name: String, point: GeoPoint): FavoritePlace? {
        if (!point.isValidLatLon()) return null
        val place = FavoritePlace(
            id = UUID.randomUUID().toString(),
            name = name.trim().ifBlank { defaultName(_favorites.value.size + 1) },
            point = point,
            createdAtMs = System.currentTimeMillis(),
        )
        commit(listOf(place) + _favorites.value)
        return place
    }

    fun rename(id: String, name: String) {
        val clean = name.trim()
        if (clean.isEmpty()) return
        commit(_favorites.value.map { if (it.id == id) it.copy(name = clean) else it })
    }

    /** Puts a deleted place back at [index] (snackbar 撤销). */
    fun restore(place: FavoritePlace, index: Int) {

        val current = _favorites.value.filterNot { it.id == place.id }.toMutableList()
        current.add(index.coerceIn(0, current.size), place)
        commit(current)
    }

    fun remove(id: String) {
        commit(_favorites.value.filterNot { it.id == id })
    }

    private fun commit(list: List<FavoritePlace>) {
        _favorites.value = list
        try {
            prefs.edit {
                putString(KEY_FAVORITES, json.encodeToString(ListSerializer(FavoritePlace.serializer()), list))
            }
        } catch (t: Throwable) {
            // Ignore: persistence failures must never take the UI down.
        }
    }

    private fun read(): List<FavoritePlace> = try {
        val raw = prefs.getString(KEY_FAVORITES, null)
        if (raw.isNullOrBlank()) {
            emptyList()
        } else {
            json.decodeFromString(ListSerializer(FavoritePlace.serializer()), raw)
        }
    } catch (t: Throwable) {
        emptyList()
    }

    private companion object {
        const val PREFS_NAME = "immoral_favorites"
        const val KEY_FAVORITES = "favorites_json"

        fun defaultName(index: Int): String = "收藏点 $index"
    }
}
