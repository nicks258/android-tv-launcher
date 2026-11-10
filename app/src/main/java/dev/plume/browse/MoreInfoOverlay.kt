package dev.plume.browse

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.plume.TvFocusHelper
import dev.plume.tmdb.RatingGaugeView
import dev.plume.tmdb.TmdbDetails
import dev.plume.tmdb.TmdbImageLoader
import dev.plume.tmdb.TmdbItem
import dev.plume.tmdb.TmdbRepository

class MoreInfoOverlay(
    private val activity: Activity,
    private val repository: TmdbRepository,
    private val imageLoader: TmdbImageLoader,
    private val onWatchClicked: (item: TmdbItem) -> Unit
) {
    private val density = activity.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()

    fun show(item: TmdbItem, originatingView: View? = null) {
        val dialog = Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.window?.setBackgroundDrawable(ColorDrawable(0xF2090B10.toInt()))

        val root = FrameLayout(activity).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }

        // 1. Ambient backdrop (w1280) in top-right
        val backdropView = ImageView(activity).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        }
        root.addView(backdropView)

        if (!item.backdropPath.isNullOrBlank()) {
            imageLoader.load(item.backdropPath, TmdbImageLoader.SIZE_BACKDROP, 1920, 1080) { bmp ->
                if (bmp != null) backdropView.setImageBitmap(bmp)
            }
        }

        // Scrim over backdrop: dark gradient left-to-right and bottom-up
        val scrimView = View(activity).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(0xFF090B10.toInt(), 0xDD090B10.toInt(), 0x99090B10.toInt(), 0x77090B10.toInt())
            )
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        }
        root.addView(scrimView)

        val bottomScrim = View(activity).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.BOTTOM_TOP,
                intArrayOf(0xFF090B10.toInt(), 0xCC090B10.toInt(), Color.TRANSPARENT)
            )
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, dp(200), Gravity.BOTTOM)
        }
        root.addView(bottomScrim)

        // 2. Content ScrollView
        val scroll = ScrollView(activity).apply {
            isVerticalScrollBarEnabled = true
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            setPadding(dp(48), dp(32), dp(48), dp(32))
        }

        val contentLayout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(dp(540), ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        // Eyebrow
        val eyebrow = TextView(activity).apply {
            text = "${item.mediaType.uppercase()} · DETAILS"
            textSize = 10f
            setTextColor(0xFF42D6C5.toInt())
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            letterSpacing = 0.08f
        }
        contentLayout.addView(eyebrow)

        // Title (Sora 700, 48px / 24sp)
        val titleView = TextView(activity).apply {
            text = item.title
            textSize = 24f
            setTextColor(Color.WHITE)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(0, dp(6), 0, 0)
        }
        contentLayout.addView(titleView)

        // Rating row: 52px (26dp) gauge + score + vote count + release info
        val metaRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, 0)
        }

        val gauge = RatingGaugeView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(dp(28), dp(28))
            setScore(item.voteAverage)
        }
        metaRow.addView(gauge)

        val metaText = TextView(activity).apply {
            val parts = mutableListOf<String>()
            parts.add(item.formattedVotes)
            if (item.year.isNotBlank()) parts.add(item.year)
            if (item.mediaType == "tv" && item.seasonsCount > 0) parts.add("${item.seasonsCount} Seasons")
            else if (item.mediaType == "movie" && item.runtimeMinutes > 0) {
                val h = item.runtimeMinutes / 60
                val m = item.runtimeMinutes % 60
                parts.add("${h}h ${m}m")
            }
            text = parts.joinToString(" · ")
            textSize = 11f
            setTextColor(0xFF929AAA.toInt())
            setPadding(dp(12), 0, 0, 0)
        }
        metaRow.addView(metaText)
        contentLayout.addView(metaRow)

        // Full Overview
        val overviewView = TextView(activity).apply {
            text = item.overview
            textSize = 11f
            setTextColor(0xFFC7CCD6.toInt())
            lineHeight = dp(16)
            setPadding(0, dp(12), 0, dp(16))
        }
        contentLayout.addView(overviewView)

        // Action Buttons Row: "Watch →" & "Close"
        val buttonsRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, dp(20))
        }

        val btnWatch = TextView(activity).apply {
            text = "Watch →"
            textSize = 12f
            setTextColor(Color.WHITE)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply {
                setColor(0xFF8B7CFF.toInt())
                cornerRadius = 999f
            }
            setPadding(dp(22), dp(8), dp(22), dp(8))
            isFocusable = true
            isClickable = true
            setOnClickListener {
                dialog.dismiss()
                onWatchClicked(item)
            }
        }
        TvFocusHelper.attach(btnWatch, cornerRadiusPx = 999f, isButton = true, isPill = true)
        buttonsRow.addView(btnWatch)

        val btnClose = TextView(activity).apply {
            text = "Close"
            textSize = 12f
            setTextColor(Color.WHITE)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply {
                setColor(0x33FFFFFF)
                cornerRadius = 999f
                setStroke((1f * density).toInt(), 0x44FFFFFF)
            }
            setPadding(dp(22), dp(8), dp(22), dp(8))
            isFocusable = true
            isClickable = true
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = dp(12)
            }
            setOnClickListener {
                dialog.dismiss()
            }
        }
        TvFocusHelper.attach(btnClose, cornerRadiusPx = 999f, isButton = true, isPill = true)
        buttonsRow.addView(btnClose)

        contentLayout.addView(buttonsRow)

        // Cast Header & Container
        val castHeader = TextView(activity).apply {
            text = "Top Cast"
            textSize = 14f
            setTextColor(Color.WHITE)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(0, dp(4), 0, dp(6))
            visibility = View.GONE
        }
        contentLayout.addView(castHeader)

        val castView = TextView(activity).apply {
            textSize = 11f
            setTextColor(0xFF929AAA.toInt())
            lineHeight = dp(15)
            setPadding(0, 0, 0, dp(16))
            visibility = View.GONE
        }
        contentLayout.addView(castView)

        // Seasons Container for TV
        val seasonsHeader = TextView(activity).apply {
            text = "Seasons & Episodes"
            textSize = 14f
            setTextColor(Color.WHITE)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(0, dp(4), 0, dp(6))
            visibility = View.GONE
        }
        contentLayout.addView(seasonsHeader)

        val seasonsLayout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(24))
            visibility = View.GONE
        }
        contentLayout.addView(seasonsLayout)

        scroll.addView(contentLayout)
        root.addView(scroll)

        dialog.setContentView(root)

        dialog.setOnDismissListener {
            originatingView?.requestFocus()
        }

        dialog.show()
        btnWatch.requestFocus()

        // Fetch detailed credits and seasons asynchronously
        repository.getDetails(item.mediaType, item.id) { details ->
            if (details != null && dialog.isShowing) {
                // Update runtime / seasons in meta text
                val parts = mutableListOf<String>()
                parts.add(details.formattedVotes)
                if (details.releaseDate.length >= 4) parts.add(details.releaseDate.substring(0, 4))
                if (details.runtimeMinutes > 0) {
                    val h = details.runtimeMinutes / 60
                    val m = details.runtimeMinutes % 60
                    parts.add(if (h > 0) "${h}h ${m}m" else "${m}m")
                } else if (details.seasonsCount > 0) {
                    parts.add("${details.seasonsCount} Seasons")
                }
                if (details.status.isNotBlank()) parts.add(details.status)
                metaText.text = parts.joinToString(" · ")

                // Cast
                if (details.cast.isNotEmpty()) {
                    castHeader.visibility = View.VISIBLE
                    castView.visibility = View.VISIBLE
                    castView.text = details.cast.joinToString("   •   ")
                }

                // Seasons list for TV
                if (details.seasons.isNotEmpty()) {
                    seasonsHeader.visibility = View.VISIBLE
                    seasonsLayout.visibility = View.VISIBLE
                    seasonsLayout.removeAllViews()
                    for (season in details.seasons) {
                        val row = LinearLayout(activity).apply {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = Gravity.CENTER_VERTICAL
                            setPadding(0, dp(4), 0, dp(4))
                        }
                        val nameTv = TextView(activity).apply {
                            text = season.name
                            textSize = 11f
                            setTextColor(Color.WHITE)
                            typeface = android.graphics.Typeface.DEFAULT_BOLD
                            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                        }
                        row.addView(nameTv)

                        val epsTv = TextView(activity).apply {
                            text = "${season.episodeCount} Episodes"
                            textSize = 10f
                            setTextColor(0xFF42D6C5.toInt())
                        }
                        row.addView(epsTv)
                        seasonsLayout.addView(row)
                    }
                }
            }
        }
    }

    private val TmdbDetails.formattedVotes: String
        get() = when {
            voteCount >= 1000 -> String.format(java.util.Locale.US, "%.1fK votes", voteCount / 1000.0)
            else -> "$voteCount votes"
        }
}
