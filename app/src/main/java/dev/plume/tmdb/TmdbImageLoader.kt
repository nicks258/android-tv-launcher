package dev.plume.tmdb

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.LruCache
import androidx.palette.graphics.Palette
import dev.plume.DeviceTier
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors

class TmdbImageLoader(
    private val context: Context,
    private val cache: TmdbCache
) {
    companion object {
        private const val TAG = "TmdbImageLoader"
        const val BASE_IMAGE_URL = "https://image.tmdb.org/t/p/"
        const val SIZE_POSTER = "w342"
        const val SIZE_BACKDROP = "w1280"
        const val SIZE_LOGO = "w300"
    }

    private val isLowTier: Boolean get() = DeviceTier.isLowTier(context)
    private val diskCacheDir: File = File(context.cacheDir, "tmdb_images").apply { mkdirs() }
    private val mainHandler = Handler(Looper.getMainLooper())
    private val diskExecutor = Executors.newFixedThreadPool(2)

    // Bounded memory cache: halved on low-tier (~4-8MB on low tier, up to 24MB on high tier)
    private val maxMemory = (Runtime.getRuntime().maxMemory() / 1024).toInt()
    private val cacheSize: Int
        get() = if (isLowTier) {
            (maxMemory / 16).coerceIn(2048, 8192)
        } else {
            (maxMemory / 8).coerceIn(4096, 24576)
        }

    private val memCache = object : LruCache<String, Bitmap>(cacheSize) {
        override fun sizeOf(key: String, bitmap: Bitmap): Int {
            return bitmap.byteCount / 1024
        }
    }

    fun clearMemoryCache() {
        memCache.evictAll()
    }

    private fun md5(str: String): String {
        val digest = MessageDigest.getInstance("MD5")
        val bytes = digest.digest(str.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun getCachedBitmap(key: String): Bitmap? = memCache.get(key)

    fun load(
        urlPath: String?,
        size: String = SIZE_POSTER,
        targetWidth: Int = 0,
        targetHeight: Int = 0,
        onLoaded: (Bitmap?) -> Unit
    ) {
        if (urlPath.isNullOrBlank()) {
            onLoaded(null)
            return
        }

        val effectiveW = if (targetWidth <= 0) (if (size == SIZE_BACKDROP) (if (isLowTier) 960 else 1920) else 220) else targetWidth
        val effectiveH = if (targetHeight <= 0) (if (size == SIZE_BACKDROP) (if (isLowTier) 540 else 1080) else 330) else targetHeight

        val fullUrl = if (urlPath.startsWith("http")) urlPath else "$BASE_IMAGE_URL$size$urlPath"
        val cacheKey = "$size:$urlPath:${effectiveW}x${effectiveH}"

        val memHit = memCache.get(cacheKey)
        if (memHit != null) {
            onLoaded(memHit)
            return
        }

        diskExecutor.execute {
            val bitmap = loadFromDiskOrNetwork(fullUrl, cacheKey, effectiveW, effectiveH)
            if (bitmap != null) {
                memCache.put(cacheKey, bitmap)
            }
            mainHandler.post {
                onLoaded(bitmap)
            }
        }
    }

    fun prefetch(urlPath: String?, size: String = SIZE_POSTER) {
        if (urlPath.isNullOrBlank()) return
        val w = if (size == SIZE_BACKDROP) (if (isLowTier) 960 else 1920) else 220
        val h = if (size == SIZE_BACKDROP) (if (isLowTier) 540 else 1080) else 330
        val cacheKey = "$size:$urlPath:${w}x${h}"
        if (memCache.get(cacheKey) != null) return

        val fullUrl = if (urlPath.startsWith("http")) urlPath else "$BASE_IMAGE_URL$size$urlPath"
        diskExecutor.execute {
            val bitmap = loadFromDiskOrNetwork(fullUrl, cacheKey, w, h)
            if (bitmap != null) {
                memCache.put(cacheKey, bitmap)
            }
        }
    }

    fun extractDominantColor(id: Int, posterPath: String?, onColorExtracted: (Int) -> Unit) {
        // Section 4: On Low tier, no Palette extraction (use surface color)
        if (isLowTier) {
            onColorExtracted(0xFF141821.toInt())
            return
        }

        val cachedColor = cache.getDominantColor(id)
        if (cachedColor != null) {
            onColorExtracted(cachedColor)
            return
        }

        if (posterPath.isNullOrBlank()) {
            onColorExtracted(0xFF141821.toInt())
            return
        }

        load(posterPath, SIZE_POSTER, 80, 120) { bmp ->
            if (bmp == null) {
                onColorExtracted(0xFF141821.toInt())
                return@load
            }

            diskExecutor.execute {
                try {
                    val palette = Palette.from(bmp).generate()
                    val swatch = palette.darkMutedSwatch
                        ?: palette.darkVibrantSwatch
                        ?: palette.dominantSwatch

                    val color = swatch?.rgb ?: 0xFF141821.toInt()
                    val darkened = darkenColor(color, 0.75f)
                    cache.putDominantColor(id, darkened)
                    mainHandler.post {
                        onColorExtracted(darkened)
                    }
                } catch (e: Exception) {
                    mainHandler.post {
                        onColorExtracted(0xFF141821.toInt())
                    }
                }
            }
        }
    }

    private fun darkenColor(color: Int, factor: Float): Int {
        val a = Color.alpha(color)
        val r = (Color.red(color) * factor).toInt().coerceIn(0, 255)
        val g = (Color.green(color) * factor).toInt().coerceIn(0, 255)
        val b = (Color.blue(color) * factor).toInt().coerceIn(0, 255)
        return Color.argb(a, r, g, b)
    }

    private fun loadFromDiskOrNetwork(
        fullUrl: String,
        cacheKey: String,
        targetWidth: Int,
        targetHeight: Int
    ): Bitmap? {
        val file = File(diskCacheDir, md5(cacheKey))
        if (file.exists() && file.length() > 0) {
            val bmp = decodeSampledBitmap(file.absolutePath, targetWidth, targetHeight)
            if (bmp != null) return bmp
        }

        var conn: HttpURLConnection? = null
        try {
            conn = (URL(fullUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 4000
                readTimeout = 4000
                doInput = true
            }
            if (conn.responseCode in 200..299) {
                val tempFile = File(diskCacheDir, md5(cacheKey) + ".tmp")
                conn.inputStream.use { input ->
                    FileOutputStream(tempFile).use { output ->
                        input.copyTo(output)
                    }
                }
                if (tempFile.exists() && tempFile.length() > 0) {
                    tempFile.renameTo(file)
                    return decodeSampledBitmap(file.absolutePath, targetWidth, targetHeight)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to download image $fullUrl: ${e.message}")
        } finally {
            conn?.disconnect()
        }
        return null
    }

    private fun decodeSampledBitmap(path: String, reqWidth: Int, reqHeight: Int): Bitmap? {
        val options = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeFile(path, options)

        val height = options.outHeight
        val width = options.outWidth
        var inSampleSize = 1

        val targetW = if (reqWidth > 0) reqWidth else (if (isLowTier) 960 else 1920)
        val targetH = if (reqHeight > 0) reqHeight else (if (isLowTier) 540 else 1080)

        if (height > targetH || width > targetW) {
            val halfHeight = height / 2
            val halfWidth = width / 2
            while ((halfHeight / inSampleSize) >= targetH && (halfWidth / inSampleSize) >= targetW) {
                inSampleSize *= 2
            }
        }

        val decodeOptions = BitmapFactory.Options().apply {
            this.inSampleSize = inSampleSize
            inPreferredConfig = Bitmap.Config.RGB_565 // 50% memory saving
            inDither = true
        }
        return BitmapFactory.decodeFile(path, decodeOptions)
    }
}
