package com.immoral.location.net

import android.util.Log
import java.net.HttpURLConnection
import java.net.URL

/**
 * Minimal blocking HTTP GET abstraction used by the routing clients.
 *
 * It exists so the routing *logic* (URL building, JSON parsing, fallback ordering) is unit-testable
 * without a network connection: tests inject a fake and assert on the URL that was requested.
 */
fun interface HttpFetcher {
    /**
     * Performs a GET request.
     *
     * @return the response body, or `null` when the request failed (no network, non-2xx, timeout).
     *   Implementations must never throw.
     */
    fun get(url: String, timeoutMs: Int): String?
}

/** [HttpFetcher] backed by [HttpURLConnection]. Never throws; failures come back as `null`. */
class UrlConnectionFetcher(
    private val userAgent: String,
    private val accept: String = "application/json",
) : HttpFetcher {

    override fun get(url: String, timeoutMs: Int): String? = try {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            requestMethod = "GET"
            setRequestProperty("User-Agent", userAgent)
            setRequestProperty("Accept", accept)
        }
        try {
            if (connection.responseCode !in 200..299) {
                Log.w(TAG, "HTTP ${connection.responseCode} for ${url.take(160)}")
                null
            } else {
                connection.inputStream.bufferedReader().use { it.readText() }
            }
        } finally {
            try {
                connection.disconnect()
            } catch (ignored: Throwable) {
                // ignore
            }
        }
    } catch (t: Throwable) {
        Log.w(TAG, "GET failed: ${t.javaClass.simpleName} ${t.message}")
        null
    }

    private companion object {
        const val TAG = "RouteHttp"
    }
}
