package dev.plume.tmdb

data class TmdbItem(
    val id: Int,
    val title: String,
    val originalTitle: String,
    val overview: String,
    val posterPath: String?,
    val backdropPath: String?,
    val voteAverage: Double,
    val voteCount: Int,
    val releaseDate: String,
    val genreIds: List<Int>,
    val mediaType: String, // "movie" or "tv"
    var runtimeMinutes: Int = 0,
    var seasonsCount: Int = 0,
    var status: String = "",
    var logoPath: String? = null
) {
    val year: String
        get() = if (releaseDate.length >= 4) releaseDate.substring(0, 4) else ""

    val formattedRating: String
        get() = String.format(java.util.Locale.US, "%.1f", voteAverage)

    val formattedVotes: String
        get() = when {
            voteCount >= 1000 -> String.format(java.util.Locale.US, "%.1fK votes", voteCount / 1000.0)
            else -> "$voteCount votes"
        }
}

data class TmdbGenre(
    val id: Int,
    val name: String
)

data class TmdbConfiguration(
    val secureBaseUrl: String = "https://image.tmdb.org/t/p/",
    val posterSizes: List<String> = listOf("w92", "w154", "w185", "w342", "w500", "w780", "original"),
    val backdropSizes: List<String> = listOf("w300", "w780", "w1280", "original"),
    val logoSizes: List<String> = listOf("w45", "w92", "w154", "w185", "w300", "w500", "original")
)

data class TmdbDetails(
    val id: Int,
    val title: String,
    val originalTitle: String,
    val overview: String,
    val posterPath: String?,
    val backdropPath: String?,
    val voteAverage: Double,
    val voteCount: Int,
    val releaseDate: String,
    val runtimeMinutes: Int,
    val seasonsCount: Int,
    val status: String,
    val genres: List<String>,
    val tagline: String,
    val logoPath: String?,
    val cast: List<String>,
    val seasons: List<TmdbSeasonInfo>
)

data class TmdbSeasonInfo(
    val seasonNumber: Int,
    val name: String,
    val episodeCount: Int,
    val airDate: String?,
    val posterPath: String?
)
