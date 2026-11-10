package dev.plume

import android.app.ActivityManager
import android.content.Context

enum class DeviceTierLevel {
    LOW,
    MID,
    HIGH
}

enum class PerformanceMode {
    AUTO,
    QUALITY,
    PERFORMANCE
}

object DeviceTier {
    private var detectedTier: DeviceTierLevel? = null

    fun getTier(context: Context): DeviceTierLevel {
        val prefs = context.getSharedPreferences("plume_prefs", Context.MODE_PRIVATE)
        val override = prefs.getString("perf_mode", "auto")?.lowercase()
        when (override) {
            "performance" -> return DeviceTierLevel.LOW
            "quality" -> return DeviceTierLevel.HIGH
        }

        detectedTier?.let { return it }

        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        am?.getMemoryInfo(memInfo)
        val totalRamMb = memInfo.totalMem / (1024 * 1024)
        val isLowRam = am?.isLowRamDevice == true
        val memoryClass = am?.memoryClass ?: 192

        val tier = when {
            isLowRam || totalRamMb < 2100 || memoryClass < 192 -> DeviceTierLevel.LOW
            totalRamMb >= 3000 && !isLowRam -> DeviceTierLevel.HIGH
            else -> DeviceTierLevel.MID
        }
        detectedTier = tier
        return tier
    }

    fun isLowTier(context: Context): Boolean = getTier(context) == DeviceTierLevel.LOW

    fun getPerformanceMode(context: Context): PerformanceMode {
        val prefs = context.getSharedPreferences("plume_prefs", Context.MODE_PRIVATE)
        return when (prefs.getString("perf_mode", "auto")?.lowercase()) {
            "performance" -> PerformanceMode.PERFORMANCE
            "quality" -> PerformanceMode.QUALITY
            else -> PerformanceMode.AUTO
        }
    }

    fun setPerformanceMode(context: Context, mode: PerformanceMode) {
        val prefs = context.getSharedPreferences("plume_prefs", Context.MODE_PRIVATE)
        val str = when (mode) {
            PerformanceMode.PERFORMANCE -> "performance"
            PerformanceMode.QUALITY -> "quality"
            PerformanceMode.AUTO -> "auto"
        }
        prefs.edit().putString("perf_mode", str).apply()
        detectedTier = null
    }
}

