package dev.plume.tmdb

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.Semaphore

class TmdbClient(
    private var tokenProvider: () -> String
) {
    companion object {
        private const val TAG = "TmdbClient"
        private const val BASE_URL = "https://api.themoviedb.org/3"
        private const val TIMEOUT_MS = 5000
        private val concurrencyLimit = Semaphore(3)
    }

    fun getDeviceLanguage(): String {
        val locale = Locale.getDefault()
        val lang = locale.language
        val country = locale.country
        return if (country.isNotBlank()) "$lang-$country" else lang
    }

    fun request(endpoint: String, params: Map<String, String> = emptyMap()): String? {
        val token = tokenProvider().trim()
        if (token.isBlank()) {
            Log.d(TAG, "No TMDB token provided, skipping request: $endpoint")
            return null
        }

        concurrencyLimit.acquire()
        try {
            var attempt = 0
            while (attempt < 3) {
                attempt++
                val isBearer = token.length > 40 || token.startsWith("eyJ")
                val queryParams = params.toMutableMap()
                if (!queryParams.containsKey("include_adult")) {
                    queryParams["include_adult"] = "false"
                }
                if (!queryParams.containsKey("language")) {
                    queryParams["language"] = getDeviceLanguage()
                }
                if (!isBearer) {
                    queryParams["api_key"] = token
                }

                val queryString = queryParams.entries.joinToString("&") { (k, v) ->
                    "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}"
                }

                val fullUrl = if (queryString.isNotBlank()) "$BASE_URL$endpoint?$queryString" else "$BASE_URL$endpoint"
                var conn: HttpURLConnection? = null
                try {
                    conn = (URL(fullUrl).openConnection() as HttpURLConnection).apply {
                        connectTimeout = TIMEOUT_MS
                        readTimeout = TIMEOUT_MS
                        requestMethod = "GET"
                        setRequestProperty("Accept", "application/json")
                        if (isBearer) {
                            setRequestProperty("Authorization", "Bearer $token")
                        }
                    }

                    val code = conn.responseCode
                    if (code == 429) {
                        Log.w(TAG, "HTTP 429 Too Many Requests on $endpoint, backing off (attempt $attempt)")
                        val retryAfterHeader = conn.getHeaderField("Retry-After")
                        val backoffMs = (retryAfterHeader?.toLongOrNull() ?: (attempt * 1000L)).coerceIn(1000L, 5000L)
                        Thread.sleep(backoffMs)
                        continue
                    }

                    if (code in 200..299) {
                        BufferedReader(InputStreamReader(conn.inputStream, "UTF-8")).use { reader ->
                            return reader.readText()
                        }
                    } else {
                        Log.w(TAG, "TMDB HTTP error $code for $endpoint")
                        return null
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "TMDB request failed for $endpoint: ${e.message}")
                    if (attempt < 3 && e !is java.net.UnknownHostException) {
                        Thread.sleep(500L * attempt)
                    } else {
                        return null
                    }
                } finally {
                    conn?.disconnect()
                }
            }
        } finally {
            concurrencyLimit.release()
        }
        return null
    }
}
