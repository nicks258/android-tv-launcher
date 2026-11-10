package dev.plume.tmdb

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class TmdbRepository(
    context: Context,
    private val client: TmdbClient,
    private val cache: TmdbCache
) {
    companion object {
        private const val TAG = "TmdbRepository"
    }

    private val executor = Executors.newFixedThreadPool(3)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    // Genre mappings cache in memory for quick name lookups
    private val movieGenreMap = mutableMapOf<Int, String>()
    private val tvGenreMap = mutableMapOf<Int, String>()

    fun getGenreName(id: Int, mediaType: String): String {
        return if (mediaType == "tv") tvGenreMap[id] ?: "Series" else movieGenreMap[id] ?: "Movie"
    }

    fun getGenreNames(ids: List<Int>, mediaType: String): List<String> {
        return ids.mapNotNull { id ->
            if (mediaType == "tv") tvGenreMap[id] else movieGenreMap[id]
        }
    }

    fun fetchGenres(mediaType: String, onLoaded: (List<TmdbGenre>) -> Unit) {
        executor.execute {
            val cacheKey = "genres:$mediaType"
            val cachedJson = cache.get(cacheKey, TmdbCache.EXPIRY_GENRES_MS)
            if (cachedJson != null) {
                val list = parseGenres(cachedJson, mediaType)
                mainHandler.post { onLoaded(list) }
                return@execute
            }

            val endpoint = if (mediaType == "tv") "/genre/tv/list" else "/genre/movie/list"
            val response = client.request(endpoint)
            if (response != null) {
                cache.put(cacheKey, response)
                val list = parseGenres(response, mediaType)
                mainHandler.post { onLoaded(list) }
            } else {
                // Fallback default genres
                val fallback = getDefaultGenres(mediaType)
                mainHandler.post { onLoaded(fallback) }
            }
        }
    }

    private fun parseGenres(jsonStr: String, mediaType: String): List<TmdbGenre> {
        val list = mutableListOf<TmdbGenre>()
        try {
            val root = JSONObject(jsonStr)
            val arr = root.optJSONArray("genres") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val id = obj.getInt("id")
                val name = obj.getString("name")
                list.add(TmdbGenre(id, name))
                if (mediaType == "tv") tvGenreMap[id] = name else movieGenreMap[id] = name
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse genres: ${e.message}")
        }
        return list
    }

    private fun getDefaultGenres(mediaType: String): List<TmdbGenre> {
        return if (mediaType == "tv") {
            listOf(
                TmdbGenre(10759, "Action & Adventure"),
                TmdbGenre(16, "Animation"),
                TmdbGenre(35, "Comedy"),
                TmdbGenre(80, "Crime"),
                TmdbGenre(99, "Documentary"),
                TmdbGenre(18, "Drama"),
                TmdbGenre(10751, "Family"),
                TmdbGenre(10762, "Kids"),
                TmdbGenre(9648, "Mystery"),
                TmdbGenre(10764, "Reality"),
                TmdbGenre(10765, "Sci-Fi & Fantasy")
            )
        } else {
            listOf(
                TmdbGenre(28, "Action"),
                TmdbGenre(12, "Adventure"),
                TmdbGenre(16, "Animation"),
                TmdbGenre(35, "Comedy"),
                TmdbGenre(80, "Crime"),
                TmdbGenre(99, "Documentary"),
                TmdbGenre(18, "Drama"),
                TmdbGenre(10751, "Family"),
                TmdbGenre(14, "Fantasy"),
                TmdbGenre(27, "Horror"),
                TmdbGenre(9648, "Mystery"),
                TmdbGenre(878, "Science Fiction"),
                TmdbGenre(53, "Thriller")
            )
        }.also { genres ->
            genres.forEach { g ->
                if (mediaType == "tv") tvGenreMap[g.id] = g.name else movieGenreMap[g.id] = g.name
            }
        }
    }

    fun getHomeHeroItems(
        windowDays: Int = 90,
        minRating: Double = 7.0,
        minMovieVotes: Int = 100,
        minTvVotes: Int = 50,
        onLoaded: (List<TmdbItem>) -> Unit
    ) {
        executor.execute {
            val cacheKey = "hero:w$windowDays:r$minRating"
            val cachedJson = cache.get(cacheKey, 6L * 60 * 60 * 1000) // 6 hours
            if (cachedJson != null) {
                val items = parseItems(cachedJson, "mixed", minRating)
                if (items.isNotEmpty()) {
                    mainHandler.post { onLoaded(items) }
                    return@execute
                }
            }

            val cal = Calendar.getInstance()
            val todayStr = dateFormat.format(cal.time)
            cal.add(Calendar.DAY_OF_YEAR, -windowDays)
            val pastStr = dateFormat.format(cal.time)

            // Discover movies
            val movieParams = mapOf(
                "primary_release_date.gte" to pastStr,
                "primary_release_date.lte" to todayStr,
                "vote_average.gte" to minRating.toString(),
                "vote_count.gte" to minMovieVotes.toString(),
                "sort_by" to "popularity.desc"
            )
            val movieJson = client.request("/discover/movie", movieParams)
            val movies = if (movieJson != null) parseItems(movieJson, "movie", minRating) else emptyList()

            // Discover TV series
            val tvParams = mapOf(
                "first_air_date.gte" to pastStr,
                "first_air_date.lte" to todayStr,
                "vote_average.gte" to minRating.toString(),
                "vote_count.gte" to minTvVotes.toString(),
                "sort_by" to "popularity.desc"
            )
            val tvJson = client.request("/discover/tv", tvParams)
            val tvs = if (tvJson != null) parseItems(tvJson, "tv", minRating) else emptyList()

            // Interleave movies and TV, deduplicating and limiting to 10
            val mixed = mutableListOf<TmdbItem>()
            val maxLen = maxOf(movies.size, tvs.size)
            for (i in 0 until maxLen) {
                if (i < movies.size && mixed.size < 10 && movies[i].voteAverage > minRating) {
                    mixed.add(movies[i])
                }
                if (i < tvs.size && mixed.size < 10 && tvs[i].voteAverage > minRating) {
                    mixed.add(tvs[i])
                }
            }

            // Fetch details (logo and runtime/seasons) for top items
            for (item in mixed.take(4)) {
                fetchItemDetailsSync(item)
            }

            if (mixed.isNotEmpty()) {
                val jsonArr = JSONArray()
                mixed.forEach { jsonArr.put(serializeItem(it)) }
                val wrapper = JSONObject().apply { put("results", jsonArr) }
                cache.put(cacheKey, wrapper.toString())
                mainHandler.post { onLoaded(mixed) }
            } else {
                mainHandler.post { onLoaded(emptyList()) }
            }
        }
    }

    fun getBrowseRow(
        mediaType: String,
        genreId: Int,
        sort: String = "popularity.desc",
        page: Int = 1,
        minRating: Double = 6.5,
        minVotes: Int = if (mediaType == "tv") 100 else 200,
        onLoaded: (List<TmdbItem>) -> Unit
    ) {
        executor.execute {
            val cacheKey = "browse:$mediaType:g$genreId:s$sort:p$page"
            val cachedJson = cache.get(cacheKey, TmdbCache.EXPIRY_ROW_MS)
            if (cachedJson != null) {
                val items = parseItems(cachedJson, mediaType, minRating)
                mainHandler.post { onLoaded(items) }
                return@execute
            }

            val endpoint = if (mediaType == "tv") "/discover/tv" else "/discover/movie"
            val params = mutableMapOf(
                "vote_average.gte" to minRating.toString(),
                "vote_count.gte" to minVotes.toString(),
                "sort_by" to sort,
                "page" to page.toString()
            )
            if (genreId > 0) {
                params["with_genres"] = genreId.toString()
            }

            val response = client.request(endpoint, params)
            if (response != null) {
                cache.put(cacheKey, response)
                val items = parseItems(response, mediaType, minRating)
                mainHandler.post { onLoaded(items) }
            } else {
                mainHandler.post { onLoaded(emptyList()) }
            }
        }
    }

    fun getDetails(mediaType: String, id: Int, onLoaded: (TmdbDetails?) -> Unit) {
        executor.execute {
            val cacheKey = "details:$mediaType:$id"
            val cachedJson = cache.get(cacheKey, TmdbCache.EXPIRY_DETAILS_MS)
            if (cachedJson != null) {
                val details = parseDetails(cachedJson, mediaType)
                mainHandler.post { onLoaded(details) }
                return@execute
            }

            val endpoint = "/$mediaType/$id"
            val response = client.request(endpoint, mapOf("append_to_response" to "credits,images"))
            if (response != null) {
                cache.put(cacheKey, response)
                val details = parseDetails(response, mediaType)
                mainHandler.post { onLoaded(details) }
            } else {
                mainHandler.post { onLoaded(null) }
            }
        }
    }

    private fun fetchItemDetailsSync(item: TmdbItem) {
        try {
            val endpoint = "/${item.mediaType}/${item.id}"
            val response = client.request(endpoint, mapOf("append_to_response" to "images"))
            if (response != null) {
                val root = JSONObject(response)
                if (item.mediaType == "movie") {
                    item.runtimeMinutes = root.optInt("runtime", 0)
                } else {
                    item.seasonsCount = root.optInt("number_of_seasons", 0)
                    item.status = root.optString("status", "")
                }
                // Extract best logo
                val imagesObj = root.optJSONObject("images")
                val logosArr = imagesObj?.optJSONArray("logos")
                if (logosArr != null && logosArr.length() > 0) {
                    val lang = client.getDeviceLanguage().split("-")[0]
                    var bestLogo: String? = null
                    for (i in 0 until logosArr.length()) {
                        val logoObj = logosArr.getJSONObject(i)
                        val iso = logoObj.optString("iso_639_1")
                        val path = logoObj.optString("file_path")
                        if (iso == lang || iso == "en" || iso.isBlank()) {
                            bestLogo = path
                            break
                        }
                    }
                    item.logoPath = bestLogo ?: logosArr.getJSONObject(0).optString("file_path")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error fetching details for item ${item.id}: ${e.message}")
        }
    }

    private fun parseItems(jsonStr: String, defaultMediaType: String, minRating: Double): List<TmdbItem> {
        val list = mutableListOf<TmdbItem>()
        try {
            val root = JSONObject(jsonStr)
            val arr = root.optJSONArray("results") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val rating = obj.optDouble("vote_average", 0.0)
                // Strict rating check: strictly above minimum rating
                if (rating <= minRating) continue

                val id = obj.getInt("id")
                val title = if (obj.has("title")) obj.getString("title") else obj.optString("name", "")
                val originalTitle = if (obj.has("original_title")) obj.getString("original_title") else obj.optString("original_name", "")
                val overview = obj.optString("overview", "")
                val posterPath = obj.optString("poster_path").takeIf { it.isNotBlank() && it != "null" }
                val backdropPath = obj.optString("backdrop_path").takeIf { it.isNotBlank() && it != "null" }
                val voteCount = obj.optInt("vote_count", 0)
                val releaseDate = if (obj.has("release_date")) obj.optString("release_date", "") else obj.optString("first_air_date", "")
                val mediaType = obj.optString("media_type", defaultMediaType)

                val genreIds = mutableListOf<Int>()
                val genreArr = obj.optJSONArray("genre_ids")
                if (genreArr != null) {
                    for (g in 0 until genreArr.length()) {
                        genreIds.add(genreArr.getInt(g))
                    }
                }

                val item = TmdbItem(
                    id = id,
                    title = title,
                    originalTitle = originalTitle,
                    overview = overview,
                    posterPath = posterPath,
                    backdropPath = backdropPath,
                    voteAverage = rating,
                    voteCount = voteCount,
                    releaseDate = releaseDate,
                    genreIds = genreIds,
                    mediaType = mediaType,
                    runtimeMinutes = obj.optInt("runtime", 0),
                    seasonsCount = obj.optInt("number_of_seasons", 0),
                    status = obj.optString("status", ""),
                    logoPath = obj.optString("logo_path").takeIf { it.isNotBlank() && it != "null" }
                )
                list.add(item)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse items: ${e.message}")
        }
        return list
    }

    private fun parseDetails(jsonStr: String, mediaType: String): TmdbDetails? {
        return try {
            val root = JSONObject(jsonStr)
            val id = root.getInt("id")
            val title = if (root.has("title")) root.getString("title") else root.optString("name", "")
            val originalTitle = if (root.has("original_title")) root.getString("original_title") else root.optString("original_name", "")
            val overview = root.optString("overview", "")
            val posterPath = root.optString("poster_path").takeIf { it.isNotBlank() && it != "null" }
            val backdropPath = root.optString("backdrop_path").takeIf { it.isNotBlank() && it != "null" }
            val voteAverage = root.optDouble("vote_average", 0.0)
            val voteCount = root.optInt("vote_count", 0)
            val releaseDate = if (root.has("release_date")) root.optString("release_date", "") else root.optString("first_air_date", "")
            val runtime = root.optInt("runtime", 0)
            val seasonsCount = root.optInt("number_of_seasons", 0)
            val status = root.optString("status", "")
            val tagline = root.optString("tagline", "")

            val genres = mutableListOf<String>()
            val genreArr = root.optJSONArray("genres")
            if (genreArr != null) {
                for (i in 0 until genreArr.length()) {
                    genres.add(genreArr.getJSONObject(i).getString("name"))
                }
            }

            // Top 6 cast members
            val cast = mutableListOf<String>()
            val creditsObj = root.optJSONObject("credits")
            val castArr = creditsObj?.optJSONArray("cast")
            if (castArr != null) {
                for (i in 0 until minOf(6, castArr.length())) {
                    cast.add(castArr.getJSONObject(i).getString("name"))
                }
            }

            // Seasons info for series
            val seasons = mutableListOf<TmdbSeasonInfo>()
            val seasonsArr = root.optJSONArray("seasons")
            if (seasonsArr != null) {
                for (i in 0 until seasonsArr.length()) {
                    val sObj = seasonsArr.getJSONObject(i)
                    val sNum = sObj.getInt("season_number")
                    val sName = sObj.getString("name")
                    val epCount = sObj.optInt("episode_count", 0)
                    val airDate = sObj.optString("air_date").takeIf { it.isNotBlank() && it != "null" }
                    val sPoster = sObj.optString("poster_path").takeIf { it.isNotBlank() && it != "null" }
                    seasons.add(TmdbSeasonInfo(sNum, sName, epCount, airDate, sPoster))
                }
            }

            // Extract logo
            var logoPath: String? = null
            val imagesObj = root.optJSONObject("images")
            val logosArr = imagesObj?.optJSONArray("logos")
            if (logosArr != null && logosArr.length() > 0) {
                val lang = client.getDeviceLanguage().split("-")[0]
                for (i in 0 until logosArr.length()) {
                    val lObj = logosArr.getJSONObject(i)
                    val iso = lObj.optString("iso_639_1")
                    if (iso == lang || iso == "en" || iso.isBlank()) {
                        logoPath = lObj.optString("file_path")
                        break
                    }
                }
                if (logoPath == null) logoPath = logosArr.getJSONObject(0).optString("file_path")
            }

            TmdbDetails(
                id = id,
                title = title,
                originalTitle = originalTitle,
                overview = overview,
                posterPath = posterPath,
                backdropPath = backdropPath,
                voteAverage = voteAverage,
                voteCount = voteCount,
                releaseDate = releaseDate,
                runtimeMinutes = runtime,
                seasonsCount = seasonsCount,
                status = status,
                genres = genres,
                tagline = tagline,
                logoPath = logoPath,
                cast = cast,
                seasons = seasons
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse details: ${e.message}")
            null
        }
    }

    private fun serializeItem(item: TmdbItem): JSONObject {
        return JSONObject().apply {
            put("id", item.id)
            put("title", item.title)
            put("original_title", item.originalTitle)
            put("overview", item.overview)
            put("poster_path", item.posterPath)
            put("backdrop_path", item.backdropPath)
            put("vote_average", item.voteAverage)
            put("vote_count", item.voteCount)
            put("release_date", item.releaseDate)
            put("media_type", item.mediaType)
            put("runtime", item.runtimeMinutes)
            put("number_of_seasons", item.seasonsCount)
            put("status", item.status)
            put("logo_path", item.logoPath)
            put("genre_ids", JSONArray(item.genreIds))
        }
    }
}
