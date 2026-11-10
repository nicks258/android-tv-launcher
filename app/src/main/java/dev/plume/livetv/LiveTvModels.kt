package dev.plume.livetv

/**
 * Data models for the Plume Live TV guide powered by Onyx TV ContentProvider.
 */
data class CountryItem(
    val code: String,
    val name: String,
    val channelCount: Int
)

data class ChannelItem(
    val channelId: String,
    val number: String,
    val name: String,
    val logoUri: String?,
    val country: String,
    val isFavorite: Boolean
)

data class ProgrammeItem(
    val channelId: String,
    val start: Long,
    val end: Long,
    val title: String,
    val genre: String?,
    val imageUri: String?,
    val hasCatchup: Boolean
) {
    fun isAiringNow(now: Long = System.currentTimeMillis()): Boolean = now in start until end
    fun isPast(now: Long = System.currentTimeMillis()): Boolean = end <= now
}

enum class OnyxState {
    READY,
    NO_PLAYLIST,
    TURNED_OFF,
    NOT_INSTALLED
}

typealias NyxState = OnyxState
