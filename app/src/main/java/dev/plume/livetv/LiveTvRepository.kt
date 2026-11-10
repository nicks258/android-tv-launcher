package dev.plume.livetv

import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class LiveTvRepository(private val context: Context) {

    companion object {
        private const val TAG = "LiveTvRepository"
        const val ONYX_PACKAGE = "com.onyxtv.app"
        const val ONYX_DEBUG_PACKAGE = "com.onyxtv.app.debug"
        @Deprecated("Replaced by ONYX_PACKAGE", ReplaceWith("ONYX_PACKAGE"))
        const val NYX_PACKAGE = ONYX_PACKAGE
        const val QUERY_TIMEOUT_MS = 15000L

        fun resolveActivePackage(context: Context): String {
            val pm = context.packageManager
            for (pkg in listOf(ONYX_PACKAGE, ONYX_DEBUG_PACKAGE)) {
                try {
                    val info = pm.getApplicationInfo(pkg, 0)
                    if (info.enabled) return pkg
                } catch (e: PackageManager.NameNotFoundException) {}
            }
            return ONYX_PACKAGE
        }
    }

    fun getActivePackage(): String = resolveActivePackage(context)
    val activeAuthority: String get() = "${getActivePackage()}.provider"

    val uriCountries: Uri get() = Uri.parse("content://$activeAuthority/countries")
    val uriChannels: Uri get() = Uri.parse("content://$activeAuthority/channels")
    val uriProgrammes: Uri get() = Uri.parse("content://$activeAuthority/programmes")
    val uriLastWatched: Uri get() = Uri.parse("content://$activeAuthority/last_watched_channel")
    val uriSearch: Uri get() = Uri.parse("content://$activeAuthority/search")

    private val executor = Executors.newFixedThreadPool(2)
    private val mainHandler = Handler(Looper.getMainLooper())

    // In-memory cache for the current country; dropped when leaving tab
    private var cachedCountryCode: String? = null
    private val channelCache = mutableListOf<ChannelItem>()
    private val programmeCache = mutableMapOf<String, MutableList<ProgrammeItem>>()

    private var contentObserver: ContentObserver? = null

    fun dropCache() {
        cachedCountryCode = null
        channelCache.clear()
        programmeCache.clear()
    }

    fun checkOnyxState(): OnyxState {
        val pm = context.packageManager
        var anyFound = false
        for (pkg in listOf(ONYX_PACKAGE, ONYX_DEBUG_PACKAGE)) {
            try {
                val appInfo = pm.getApplicationInfo(pkg, 0)
                if (appInfo.enabled) return OnyxState.READY
                anyFound = true
            } catch (e: PackageManager.NameNotFoundException) {}
        }
        return if (anyFound) OnyxState.TURNED_OFF else OnyxState.NOT_INSTALLED
    }

    fun checkNyxState(): OnyxState = checkOnyxState()

    fun getCountriesAsync(callback: (List<CountryItem>) -> Unit) {
        executor.execute {
            val future = executor.submit<List<CountryItem>> {
                val countries = mutableListOf<CountryItem>()
                try {
                    val cursor = context.contentResolver.query(
                        uriCountries,
                        null,
                        null,
                        null,
                        null
                    )
                    cursor?.use { c ->
                        val codeIdx = c.getColumnIndex("code")
                        val nameIdx = c.getColumnIndex("name")
                        val countIdx = c.getColumnIndex("channel_count")
                        while (c.moveToNext()) {
                            val code = if (codeIdx >= 0) c.getString(codeIdx) ?: "" else ""
                            val name = if (nameIdx >= 0) c.getString(nameIdx) ?: "" else ""
                            val count = if (countIdx >= 0) c.getInt(countIdx) else 0
                            if (code.isNotEmpty()) {
                                countries.add(CountryItem(code, name, count))
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to query countries: ${e.message}")
                }
                countries
            }

            val list = try {
                future.get(QUERY_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            } catch (e: Exception) {
                Log.w(TAG, "Timeout or error fetching countries: ${e.message}")
                emptyList()
            }

            mainHandler.post { callback(list) }
        }
    }

    fun getChannelsAsync(
        countryCode: String,
        query: String? = null,
        offset: Int = 0,
        limit: Int = 50,
        callback: (List<ChannelItem>) -> Unit
    ) {
        if (query.isNullOrBlank() && offset == 0 && cachedCountryCode == countryCode && channelCache.isNotEmpty()) {
            callback(ArrayList(channelCache))
            return
        }

        executor.execute {
            val future = executor.submit<List<ChannelItem>> {
                val channels = mutableListOf<ChannelItem>()
                try {
                    val uriBuilder = uriChannels.buildUpon()
                        .appendQueryParameter("country", countryCode)
                        .appendQueryParameter("offset", offset.toString())
                        .appendQueryParameter("limit", limit.toString())
                    if (!query.isNullOrBlank()) {
                        uriBuilder.appendQueryParameter("query", query)
                    }

                    val cursor = context.contentResolver.query(
                        uriBuilder.build(),
                        null,
                        null,
                        null,
                        null
                    )
                    cursor?.use { c ->
                        val idIdx = c.getColumnIndex("channel_id")
                        val numIdx = c.getColumnIndex("number")
                        val nameIdx = c.getColumnIndex("name")
                        val logoIdx = c.getColumnIndex("logo_uri")
                        val countryIdx = c.getColumnIndex("country")
                        val favIdx = c.getColumnIndex("is_favorite")
                        while (c.moveToNext()) {
                            val id = if (idIdx >= 0) c.getString(idIdx) ?: "" else ""
                            val num = if (numIdx >= 0) c.getString(numIdx) ?: "" else ""
                            val name = if (nameIdx >= 0) c.getString(nameIdx) ?: "" else ""
                            val logo = if (logoIdx >= 0) c.getString(logoIdx) else null
                            val cCode = if (countryIdx >= 0) c.getString(countryIdx) ?: countryCode else countryCode
                            val isFav = if (favIdx >= 0) c.getInt(favIdx) == 1 else false
                            if (id.isNotEmpty()) {
                                channels.add(ChannelItem(id, num, name, logo, cCode, isFav))
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to query channels: ${e.message}")
                }
                channels
            }

            val list = try {
                future.get(QUERY_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            } catch (e: Exception) {
                Log.w(TAG, "Timeout or error fetching channels: ${e.message}")
                emptyList()
            }

            if (query.isNullOrBlank() && offset == 0) {
                cachedCountryCode = countryCode
                channelCache.clear()
                channelCache.addAll(list)
            }

            mainHandler.post { callback(list) }
        }
    }

    fun getProgrammesAsync(
        channelIds: List<String>,
        fromMs: Long,
        toMs: Long,
        callback: (Map<String, List<ProgrammeItem>>) -> Unit
    ) {
        if (channelIds.isEmpty()) {
            callback(emptyMap())
            return
        }

        executor.execute {
            val future = executor.submit<Map<String, List<ProgrammeItem>>> {
                val map = mutableMapOf<String, MutableList<ProgrammeItem>>()
                try {
                    val uri = uriProgrammes.buildUpon()
                        .appendQueryParameter("channel_ids", channelIds.joinToString(","))
                        .appendQueryParameter("from", fromMs.toString())
                        .appendQueryParameter("to", toMs.toString())
                        .build()

                    val cursor = context.contentResolver.query(
                        uri,
                        null,
                        null,
                        null,
                        null
                    )
                    cursor?.use { c ->
                        val idIdx = c.getColumnIndex("channel_id")
                        val startIdx = c.getColumnIndex("start")
                        val endIdx = c.getColumnIndex("end")
                        val titleIdx = c.getColumnIndex("title")
                        val genreIdx = c.getColumnIndex("genre")
                        val imgIdx = c.getColumnIndex("image_uri")
                        val catchupIdx = c.getColumnIndex("has_catchup")

                        while (c.moveToNext()) {
                            val chId = if (idIdx >= 0) c.getString(idIdx) ?: "" else ""
                            val start = if (startIdx >= 0) c.getLong(startIdx) else 0L
                            val end = if (endIdx >= 0) c.getLong(endIdx) else 0L
                            val title = if (titleIdx >= 0) c.getString(titleIdx) ?: "" else ""
                            val genre = if (genreIdx >= 0) c.getString(genreIdx) else null
                            val img = if (imgIdx >= 0) c.getString(imgIdx) else null
                            val catchup = if (catchupIdx >= 0) c.getInt(catchupIdx) == 1 else false

                            if (chId.isNotEmpty() && title.isNotEmpty()) {
                                val progs = map.getOrPut(chId) { mutableListOf() }
                                progs.add(ProgrammeItem(chId, start, end, title, genre, img, catchup))
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to query programmes: ${e.message}")
                }
                map
            }

            val result = try {
                future.get(QUERY_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            } catch (e: Exception) {
                Log.w(TAG, "Timeout or error fetching programmes: ${e.message}")
                emptyMap()
            }

            mainHandler.post { callback(result) }
        }
    }

    fun getLastWatchedChannelAsync(callback: (String?) -> Unit) {
        executor.execute {
            val future = executor.submit<String?> {
                var lastChannel: String? = null
                try {
                    val cursor = context.contentResolver.query(
                        uriLastWatched,
                        null,
                        null,
                        null,
                        null
                    )
                    cursor?.use { c ->
                        if (c.moveToFirst()) {
                            val idx = c.getColumnIndex("channel_id")
                            if (idx >= 0) {
                                lastChannel = c.getString(idx)
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to query last watched channel: ${e.message}")
                }
                lastChannel
            }

            val result = try {
                future.get(QUERY_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            } catch (e: Exception) {
                null
            }

            mainHandler.post { callback(result) }
        }
    }

    fun registerObserver(onChange: () -> Unit) {
        unregisterObserver()
        contentObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                super.onChange(selfChange, uri)
                dropCache()
                onChange()
            }
        }
        try {
            context.contentResolver.registerContentObserver(
                uriChannels,
                true,
                contentObserver!!
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register content observer: ${e.message}")
        }
    }

    fun unregisterObserver() {
        contentObserver?.let {
            try {
                context.contentResolver.unregisterContentObserver(it)
            } catch (e: Exception) {
                // Ignore
            }
            contentObserver = null
        }
    }
}
