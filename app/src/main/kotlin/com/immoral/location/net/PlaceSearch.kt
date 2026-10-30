package com.immoral.location.net

import android.util.Log
import com.immoral.location.core.model.GeoPoint
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** A single place search result. */
data class PlaceResult(
    val name: String,
    val address: String,
    val point: GeoPoint,
)

/** Outcome of a search call; either results or a Chinese error message. */
sealed interface SearchOutcome {
    data class Success(val results: List<PlaceResult>, val provider: SearchProvider) : SearchOutcome
    data class Failure(val message: String) : SearchOutcome
}

/** Which backend answered. */
enum class SearchProvider { AMAP, TIANDITU, NOMINATIM }

/**
 * Geocoding for the map search bar.
 *
 * * 天地图 geocoder is used when the user supplied a key (official, key is free and not
 *   package-bound). It answers with a single best hit.
 * * Otherwise Nominatim is used (no key, but requires a descriptive `User-Agent` and at most one
 *   request per second — enforced by [MIN_INTERVAL_MS]).
 *
 * Both are plain HTTPS calls; results are converted to WGS-84 by the caller when the active tile
 * source is GCJ-02.
 */
class PlaceSearchClient(
    private val userAgent: String,
    private val tiandituKey: String,
    private val amap: AmapPoiSearch? = null,
) {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    @Volatile
    private var lastRequestAt = 0L

    /** Searches [query]; never throws — failures come back as [SearchOutcome.Failure]. */
    fun search(query: String, limit: Int = 8): SearchOutcome {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return SearchOutcome.Failure("请输入要搜索的地点")
        CoordinateParser.parse(trimmed)?.let { point ->
            return SearchOutcome.Success(
                listOf(PlaceResult(name = "坐标 $trimmed", address = "", point = point)),
                if (tiandituKey.isBlank()) SearchProvider.NOMINATIM else SearchProvider.TIANDITU,
            )
        }
        // 1) 高德优先：池里有可用身份才走，失败（含身份被拒）再往下兜底。
        var amapFailure: String? = null
        if (amap != null) {
            when (val outcome = amap.search(trimmed, limit)) {
                is SearchOutcome.Success -> return outcome
                is SearchOutcome.Failure -> amapFailure = outcome.message
                null -> Unit
            }
        }

        // 2) 天地图 / 3) Nominatim
        val fallback = if (tiandituKey.isNotBlank()) {
            searchTianditu(trimmed) ?: searchNominatim(trimmed, limit)
        } else {
            searchNominatim(trimmed, limit)
        }
        if (fallback is SearchOutcome.Failure && amapFailure != null) {
            return SearchOutcome.Failure("$amapFailure；${fallback.message}")
        }
        return fallback
    }

    private fun searchTianditu(query: String): SearchOutcome? {
        throttle()
        val url = "https://api.tianditu.gov.cn/geocoder?ds=" +
            URLEncoder.encode("{\"keyWord\":\"$query\"}", "UTF-8") +
            "&tk=" + URLEncoder.encode(tiandituKey, "UTF-8")
        val body = get(url) ?: return SearchOutcome.Failure("天地图搜索请求失败")
        return try {
            val root = json.parseToJsonElement(body).jsonObject
            val location = root["location"] as? JsonObject
            if (location == null) {
                val message = root["msg"]?.jsonPrimitive?.contentOrNull ?: "没有找到匹配的地点"
                SearchOutcome.Failure(message)
            } else {
                val lat = location["lat"]?.jsonPrimitive?.doubleOrNull
                val lon = location["lon"]?.jsonPrimitive?.doubleOrNull
                if (lat == null || lon == null) {
                    SearchOutcome.Failure("没有找到匹配的地点")
                } else {
                    SearchOutcome.Success(
                        listOf(
                            PlaceResult(
                                name = query,
                                address = root["level"]?.jsonPrimitive?.contentOrNull ?: "",
                                point = GeoPoint(lat, lon),
                            ),
                        ),
                        SearchProvider.TIANDITU,
                    )
                }
            }
        } catch (t: Throwable) {
            SearchOutcome.Failure(t.message ?: "天地图搜索结果解析失败")
        }
    }

    private fun searchNominatim(query: String, limit: Int): SearchOutcome {
        throttle()
        val url = "https://nominatim.openstreetmap.org/search?format=jsonv2&limit=$limit" +
            "&accept-language=zh-CN&q=" + URLEncoder.encode(query, "UTF-8")
        val body = get(url) ?: return SearchOutcome.Failure("网络不可用，请检查网络后重试")
        return try {
            val array = json.parseToJsonElement(body) as? JsonArray ?: return SearchOutcome.Failure("搜索返回格式异常")
            val results = array.mapNotNull { element ->
                val obj = element as? JsonObject ?: return@mapNotNull null
                val lat = obj["lat"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
                val lon = obj["lon"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
                val display = obj["display_name"]?.jsonPrimitive?.contentOrNull ?: ""
                val name = obj["name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                    ?: display.substringBefore(',')
                PlaceResult(name = name, address = display, point = GeoPoint(lat, lon))
            }
            if (results.isEmpty()) {
                SearchOutcome.Failure("没有找到匹配的地点")
            } else {
                SearchOutcome.Success(results, SearchProvider.NOMINATIM)
            }
        } catch (t: Throwable) {
            SearchOutcome.Failure(t.message ?: "搜索结果解析失败")
        }
    }

    private fun throttle() {
        val now = System.currentTimeMillis()
        val wait = MIN_INTERVAL_MS - (now - lastRequestAt)
        if (wait > 0) {
            try {
                Thread.sleep(wait)
            } catch (ignored: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        lastRequestAt = System.currentTimeMillis()
    }

    private fun get(url: String): String? = try {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            requestMethod = "GET"
            setRequestProperty("User-Agent", userAgent)
            setRequestProperty("Accept", "application/json")
        }
        connection.useCatching {
            if (responseCode !in 200..299) {
                Log.w(TAG, "HTTP $responseCode for $url")
                null
            } else {
                inputStream.bufferedReader().use { it.readText() }
            }
        }
    } catch (t: Throwable) {
        Log.w(TAG, "request failed: ${t.javaClass.simpleName}", t)
        null
    }

    private inline fun <R> HttpURLConnection.useCatching(block: HttpURLConnection.() -> R): R? = try {
        block()
    } finally {
        try {
            disconnect()
        } catch (ignored: Throwable) {
            // ignore
        }
    }

    private companion object {
        const val TAG = "PlaceSearch"
        const val TIMEOUT_MS = 8_000
        const val MIN_INTERVAL_MS = 1_000L
    }
}

/** Parses `39.9087,116.3975`, `39.9087 116.3975`, `116.3975,39.9087` (Chinese commas accepted). */
object CoordinateParser {
    private val separators = Regex("[,\\s;\\uFF0C\\u3001]+")

    fun parse(text: String): GeoPoint? {
        val cleaned = text.trim()
        if (cleaned.isEmpty()) return null
        val parts = cleaned.split(separators).filter { it.isNotBlank() }
        if (parts.size != 2) return null
        val first = parts[0].toDoubleOrNull() ?: return null
        val second = parts[1].toDoubleOrNull() ?: return null
        // Heuristic: latitudes are limited to ±90, so a first value beyond that must be a longitude.
        val (lat, lon) = when {
            kotlin.math.abs(first) > 90.0 && kotlin.math.abs(second) <= 90.0 -> second to first
            else -> first to second
        }
        if (lat < -90.0 || lat > 90.0) return null
        if (lon < -180.0 || lon > 180.0) return null
        if (lat == 0.0 && lon == 0.0) return null
        return GeoPoint(lat, lon)
    }
}
