package dev.plume.handoff

import android.app.Activity
import android.app.Dialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import dev.plume.Qr
import dev.plume.R
import dev.plume.TvFocusHelper
import java.net.URLEncoder
import java.util.concurrent.Executors

class WatchHandoffManager(
    private val activity: Activity,
    private val onPlayInOnyx: (itemId: String?) -> Unit,
    private val onLaunchFailure: (String) -> Unit
) {
    companion object {
        private const val TAG = "WatchHandoffManager"
        const val PKG_CLOUDSTREAM_STABLE = "com.lagradost.cloudstream3"
        const val PKG_CLOUDSTREAM_PRERELEASE = "com.lagradost.cloudstream3.prerelease"
        const val PKG_ONYX = "com.onyxtv.app"
        const val PKG_ONYX_DEBUG = "com.onyxtv.app.debug"
        const val SCHEME_CLOUDSTREAM = "cloudstreamsearch"
        const val CLOUDSTREAM_OFFICIAL_URL = "https://github.com/recloudstream/cloudstream"
        const val ONYX_PROVIDER_AUTHORITY = "com.onyxtv.app.provider"
    }

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var isRegistered = false

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            // Packages changed; cache can be invalidated if needed
        }
    }

    fun register() {
        if (!isRegistered) {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_REMOVED)
                addAction(Intent.ACTION_PACKAGE_CHANGED)
                addDataScheme("package")
            }
            activity.registerReceiver(packageReceiver, filter)
            isRegistered = true
        }
    }

    fun unregister() {
        if (isRegistered) {
            try {
                activity.unregisterReceiver(packageReceiver)
            } catch (e: Exception) {
                // Ignore
            }
            isRegistered = false
        }
    }

    fun handleWatch(
        title: String,
        originalTitle: String = "",
        year: Int? = null,
        checkOnyxFirst: Boolean = true,
        useOriginalTitle: Boolean = false
    ) {
        val searchTitle = if (useOriginalTitle && originalTitle.isNotBlank()) originalTitle else title

        if (checkOnyxFirst && (isPackageInstalled(PKG_ONYX) || isPackageInstalled(PKG_ONYX_DEBUG))) {
            // Check Onyx provider off main thread with short timeout
            executor.execute {
                val onyxMatchId = queryOnyxLibrarySync(searchTitle, year)
                mainHandler.post {
                    if (onyxMatchId != null) {
                        showOnyxOrCloudStreamChooser(searchTitle, onyxMatchId)
                    } else {
                        proceedToCloudStream(searchTitle)
                    }
                }
            }
        } else {
            proceedToCloudStream(searchTitle)
        }
    }

    private fun queryOnyxLibrarySync(title: String, year: Int?): String? {
        return try {
            val pkg = dev.plume.livetv.LiveTvRepository.resolveActivePackage(activity)
            val uriBuilder = Uri.parse("content://${pkg}.provider/search").buildUpon()
                .appendQueryParameter("title", title)
            if (year != null) {
                uriBuilder.appendQueryParameter("year", year.toString())
            }
            val uri = uriBuilder.build()
            val cr = activity.contentResolver
            cr.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idCol = cursor.getColumnIndex("_id")
                    if (idCol != -1) cursor.getString(idCol) else cursor.getString(0)
                } else null
            }
        } catch (e: Exception) {
            Log.d(TAG, "Onyx library query failed: ${e.message}")
            null
        }
    }

    private fun proceedToCloudStream(searchTitle: String) {
        val pm = activity.packageManager

        // Check stable first, then prerelease
        val (pkg, state) = checkCloudStreamPackage(pm)

        when (state) {
            PackageState.ENABLED -> launchCloudStreamSearch(pkg!!, searchTitle)
            PackageState.DISABLED -> showDisabledDialog(pkg!!)
            PackageState.NOT_INSTALLED -> showNotInstalledDialog()
        }
    }

    private enum class PackageState {
        ENABLED,
        DISABLED,
        NOT_INSTALLED
    }

    private fun checkCloudStreamPackage(pm: PackageManager): Pair<String?, PackageState> {
        for (pkg in listOf(PKG_CLOUDSTREAM_STABLE, PKG_CLOUDSTREAM_PRERELEASE)) {
            try {
                val appInfo = pm.getApplicationInfo(pkg, 0)
                return if (appInfo.enabled) {
                    pkg to PackageState.ENABLED
                } else {
                    pkg to PackageState.DISABLED
                }
            } catch (e: PackageManager.NameNotFoundException) {
                // Continue to check next package
            }
        }
        return null to PackageState.NOT_INSTALLED
    }

    private fun isPackageInstalled(pkg: String): Boolean {
        return try {
            activity.packageManager.getApplicationInfo(pkg, 0)
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun launchCloudStreamSearch(pkg: String, title: String) {
        try {
            val encodedQuery = Uri.encode(title)
            val deepLink = Uri.parse("$SCHEME_CLOUDSTREAM://$encodedQuery")
            val intent = Intent(Intent.ACTION_VIEW, deepLink).apply {
                setPackage(pkg)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            // Verify if intent resolves
            val resolves = activity.packageManager.resolveActivity(intent, 0) != null
            if (resolves) {
                activity.startActivity(intent)
            } else {
                // Fallback to launcher intent with toast
                val launchIntent = activity.packageManager.getLeanbackLaunchIntentForPackage(pkg)
                    ?: activity.packageManager.getLaunchIntentForPackage(pkg)
                if (launchIntent != null) {
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    activity.startActivity(launchIntent)
                    Toast.makeText(
                        activity,
                        "Opened CloudStream — search for $title",
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    onLaunchFailure("Could not open CloudStream")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch CloudStream: ${e.message}", e)
            onLaunchFailure("Could not launch CloudStream")
        }
    }

    private fun showOnyxOrCloudStreamChooser(title: String, onyxId: String) {
        val dialog = Dialog(activity)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val density = activity.resources.displayMetrics.density
        val dp = { v: Int -> (v * density).toInt() }

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = GradientDrawable().apply {
                setColor(0xFF141821.toInt())
                cornerRadius = 16f * density
                setStroke((1f * density).toInt(), 0x33FFFFFF)
            }
            setPadding(dp(32), dp(28), dp(32), dp(28))
            layoutParams = ViewGroup.LayoutParams(dp(440), ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        val titleView = TextView(activity).apply {
            text = "Found in Onyx TV"
            textSize = 18f
            setTextColor(0xFFFFFFFF.toInt())
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        root.addView(titleView)

        val descView = TextView(activity).apply {
            text = "\"$title\" is available in your Onyx TV library. Where would you like to watch?"
            textSize = 12f
            setTextColor(0xFF929AAA.toInt())
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(24))
        }
        root.addView(descView)

        val btnOnyx = TextView(activity).apply {
            text = "Play in Onyx TV"
            textSize = 13f
            setTextColor(0xFF0B0E14.toInt())
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(0xFF00E5FF.toInt())
                cornerRadius = 12f * density
            }
            setPadding(dp(24), dp(12), dp(24), dp(12))
            isFocusable = true
            isClickable = true
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(12)
            }
            setOnClickListener {
                dialog.dismiss()
                onPlayInOnyx(onyxId)
            }
        }
        TvFocusHelper.attach(btnOnyx, cornerRadiusPx = 12f * density, isButton = true)
        root.addView(btnOnyx)

        val btnCloudStream = TextView(activity).apply {
            text = "Search in CloudStream"
            textSize = 13f
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(0x22FFFFFF)
                cornerRadius = 12f * density
                setStroke((1f * density).toInt(), 0x33FFFFFF)
            }
            setPadding(dp(24), dp(12), dp(24), dp(12))
            isFocusable = true
            isClickable = true
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setOnClickListener {
                dialog.dismiss()
                proceedToCloudStream(title)
            }
        }
        TvFocusHelper.attach(btnCloudStream, cornerRadiusPx = 12f * density, isButton = true)
        root.addView(btnCloudStream)

        dialog.setContentView(root)
        dialog.show()
        btnOnyx.requestFocus()
    }

    private fun showDisabledDialog(pkg: String) {
        val dialog = Dialog(activity)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val density = activity.resources.displayMetrics.density
        val dp = { v: Int -> (v * density).toInt() }

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = GradientDrawable().apply {
                setColor(0xFF141821.toInt())
                cornerRadius = 16f * density
                setStroke((1f * density).toInt(), 0x33FFFFFF)
            }
            setPadding(dp(32), dp(28), dp(32), dp(28))
            layoutParams = ViewGroup.LayoutParams(dp(440), ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        val titleView = TextView(activity).apply {
            text = "CloudStream is Disabled"
            textSize = 18f
            setTextColor(0xFFFFFFFF.toInt())
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        root.addView(titleView)

        val descView = TextView(activity).apply {
            text = "CloudStream is installed but currently turned off in Android Settings."
            textSize = 12f
            setTextColor(0xFF929AAA.toInt())
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(24))
        }
        root.addView(descView)

        val btnSettings = TextView(activity).apply {
            text = "Open App Info"
            textSize = 13f
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(0xFF8B7CFF.toInt())
                cornerRadius = 12f * density
            }
            setPadding(dp(24), dp(12), dp(24), dp(12))
            isFocusable = true
            isClickable = true
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(12)
            }
            setOnClickListener {
                dialog.dismiss()
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:$pkg")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                activity.startActivity(intent)
            }
        }
        TvFocusHelper.attach(btnSettings, cornerRadiusPx = 12f * density, isButton = true)
        root.addView(btnSettings)

        val btnCancel = TextView(activity).apply {
            text = "Cancel"
            textSize = 13f
            setTextColor(0xFF929AAA.toInt())
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(0x22FFFFFF)
                cornerRadius = 12f * density
            }
            setPadding(dp(24), dp(12), dp(24), dp(12))
            isFocusable = true
            isClickable = true
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setOnClickListener { dialog.dismiss() }
        }
        TvFocusHelper.attach(btnCancel, cornerRadiusPx = 12f * density, isButton = true)
        root.addView(btnCancel)

        dialog.setContentView(root)
        dialog.show()
        btnSettings.requestFocus()
    }

    private fun showNotInstalledDialog() {
        val dialog = Dialog(activity)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val density = activity.resources.displayMetrics.density
        val dp = { v: Int -> (v * density).toInt() }

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = GradientDrawable().apply {
                setColor(0xFF141821.toInt())
                cornerRadius = 16f * density
                setStroke((1f * density).toInt(), 0x33FFFFFF)
            }
            setPadding(dp(32), dp(28), dp(32), dp(28))
            layoutParams = ViewGroup.LayoutParams(dp(480), ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        val titleView = TextView(activity).apply {
            text = "CloudStream Not Installed"
            textSize = 18f
            setTextColor(0xFFFFFFFF.toInt())
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        root.addView(titleView)

        val descView = TextView(activity).apply {
            text = "Scan this QR code with your phone or visit the official project address to install CloudStream:"
            textSize = 12f
            setTextColor(0xFF929AAA.toInt())
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(16))
        }
        root.addView(descView)

        // Generate QR code locally
        val qrBitmap = generateQrBitmap(CLOUDSTREAM_OFFICIAL_URL, dp(160))
        if (qrBitmap != null) {
            val qrView = ImageView(activity).apply {
                setImageBitmap(qrBitmap)
                layoutParams = LinearLayout.LayoutParams(dp(160), dp(160)).apply {
                    bottomMargin = dp(14)
                }
            }
            root.addView(qrView)
        }

        val urlView = TextView(activity).apply {
            text = CLOUDSTREAM_OFFICIAL_URL
            textSize = 11f
            setTextColor(0xFF42D6C5.toInt())
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(20))
        }
        root.addView(urlView)

        val btnClose = TextView(activity).apply {
            text = "Close"
            textSize = 13f
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(0xFF8B7CFF.toInt())
                cornerRadius = 12f * density
            }
            setPadding(dp(24), dp(12), dp(24), dp(12))
            isFocusable = true
            isClickable = true
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setOnClickListener { dialog.dismiss() }
        }
        TvFocusHelper.attach(btnClose, cornerRadiusPx = 12f * density, isButton = true)
        root.addView(btnClose)

        dialog.setContentView(root)
        dialog.show()
        btnClose.requestFocus()
    }

    private fun generateQrBitmap(text: String, sizePx: Int): Bitmap? {
        return try {
            val modules = Qr.encode(text)
            val n = modules.size
            val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
            val scale = sizePx.toFloat() / n

            val canvas = android.graphics.Canvas(bmp)
            canvas.drawColor(0xFFFFFFFF.toInt())
            val paint = android.graphics.Paint().apply {
                color = 0xFF090B10.toInt()
                style = android.graphics.Paint.Style.FILL
            }

            for (r in 0 until n) {
                for (c in 0 until n) {
                    if (modules[r][c]) {
                        canvas.drawRect(
                            c * scale,
                            r * scale,
                            (c + 1) * scale,
                            (r + 1) * scale,
                            paint
                        )
                    }
                }
            }
            bmp
        } catch (e: Exception) {
            Log.w(TAG, "Failed to render QR bitmap: ${e.message}")
            null
        }
    }
}
