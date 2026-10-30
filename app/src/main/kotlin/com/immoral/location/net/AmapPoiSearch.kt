package com.immoral.location.net

import android.content.Context
import android.util.Log
import com.amap.api.services.core.AMapException
import com.amap.api.services.core.LatLonPoint
import com.amap.api.services.core.PoiItem
import com.amap.api.services.poisearch.PoiResult
import com.amap.api.services.poisearch.PoiSearch
import com.immoral.location.core.model.GeoPoint
import com.immoral.location.map.Gcj02
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class AmapPoiSearch(private val context: Context) {

    fun search(query: String, limit: Int = 8): SearchOutcome? {
        val app = context.applicationContext
        var lastMessage = "高德搜索不可用"
        var tried = 0
        while (tried < MAX_ATTEMPTS) {
            tried++
            when (val attempt = searchOnce(app, query, limit)) {
                is Attempt.Ok -> {
                    Log.i(TAG, "ok, identity=${activeIdentity(app)}, ${attempt.items.size} 条")
                    return SearchOutcome.Success(attempt.items, SearchProvider.AMAP)
                }
                Attempt.Empty -> return SearchOutcome.Failure("没有找到匹配的地点")
                is Attempt.Unauthorized -> {
                    Log.w(TAG, "identity=${activeIdentity(app)} rejected (code=${attempt.code})")
                    lastMessage = "高德拒绝了当前身份（code=${attempt.code}）"
                    rotateIdentity(app)
                }
                is Attempt.Error -> {
                    Log.w(TAG, "error: ${attempt.message}")
                    lastMessage = attempt.message
                    rotateIdentity(app)
                }
            }
        }
        return SearchOutcome.Failure(lastMessage)
    }

    private fun searchOnce(context: Context, query: String, limit: Int): Attempt {
        val poiQuery = try {
            PoiSearch.Query(query, "", "").apply {
                pageSize = limit.coerceIn(1, 25)
                pageNum = 0
                setCityLimit(false)
            }
        } catch (t: Throwable) {
            return Attempt.Error("构造查询失败: ${t.message}")
        }

        val latch = CountDownLatch(1)
        val resultRef = AtomicReference<PoiResult?>(null)
        val codeRef = AtomicInteger(Int.MIN_VALUE)
        val search = try {
            PoiSearch(context, poiQuery)
        } catch (t: Throwable) {
            return Attempt.Error("高德搜索初始化失败: ${t.message}")
        }
        search.setOnPoiSearchListener(object : PoiSearch.OnPoiSearchListener {
            override fun onPoiSearched(poiResult: PoiResult?, rCode: Int) {
                resultRef.set(poiResult)
                codeRef.set(rCode)
                latch.countDown()
            }

            override fun onPoiItemSearched(item: PoiItem?, rCode: Int) = Unit
        })
        try {
            search.searchPOIAsyn()
        } catch (t: Throwable) {
            return Attempt.Error("高德搜索请求失败: ${t.message}")
        }
        if (!latch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            return Attempt.Error("高德搜索超时")
        }
        val code = codeRef.get()
        if (code != AMapException.CODE_AMAP_SUCCESS) {
            return if (code in AUTH_CODES) Attempt.Unauthorized(code) else Attempt.Error("高德返回错误码 $code")
        }
        val items = resultRef.get()?.pois.orEmpty().mapNotNull { it.toPlaceResult() }
        return if (items.isEmpty()) Attempt.Empty else Attempt.Ok(items)
    }

    private fun PoiItem.toPlaceResult(): PlaceResult? {
        val ll: LatLonPoint = latLonPoint ?: return null
        if (ll.latitude == 0.0 && ll.longitude == 0.0) return null
        val title = title?.takeIf { it.isNotBlank() } ?: return null
        val wgs = Gcj02.toWgs84(GeoPoint(ll.latitude, ll.longitude))
        val address = sequenceOf(snippet, adName, cityName, provinceName)
            .filterNotNull()
            .firstOrNull { it.isNotBlank() }
            .orEmpty()
        return PlaceResult(name = title, address = address, point = wgs)
    }

    private sealed interface Attempt {
        data class Ok(val items: List<PlaceResult>) : Attempt
        data object Empty : Attempt
        data class Unauthorized(val code: Int) : Attempt
        data class Error(val message: String) : Attempt
    }

    companion object {
        private const val TAG = "AmapPoiSearch"
        private const val TIMEOUT_MS = 6_000L
        private const val MAX_ATTEMPTS = 3
        private const val PREFS = "amapkit_keys"
        private const val KEY_ACTIVE = "active"

        private val AUTH_CODES = setOf(10001, 10002, 10003, 10005, 10007, 10009, 10010, 10011, 10012, 10013, 10044)

        fun activeIdentity(context: Context): Int =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_ACTIVE, 0)

        fun rotateIdentity(context: Context): Int {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val next = prefs.getInt(KEY_ACTIVE, 0) + 1
            prefs.edit().putInt(KEY_ACTIVE, next).apply()
            return next
        }
    }
}
