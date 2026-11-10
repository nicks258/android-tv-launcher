package dev.plume.browse

import android.animation.ValueAnimator
import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import dev.plume.R
import dev.plume.tmdb.RatingGaugeView
import dev.plume.tmdb.TmdbImageLoader
import dev.plume.tmdb.TmdbItem

class ExpandableCardView(
    context: Context,
    private val imageLoader: TmdbImageLoader,
    private val onWatchClicked: (TmdbItem) -> Unit,
    private val onMoreInfoClicked: (TmdbItem, View) -> Unit,
    private val onCardFocused: (TmdbItem, View) -> Unit
) : FrameLayout(context) {

    var currentItem: TmdbItem? = null
        private set

    private val density = resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()

    private val restingWidth = dp(110) // 220px
    private val expandedWidth = dp(280) // 560px
    private val cardHeight = dp(165) // 330px
    private val posterWidth = dp(110) // 220px
    private val cornerRadiusPx = 9f * density // 18px

    private val isLowRam: Boolean
        get() = dev.plume.DeviceTier.isLowTier(context)

    // Outer card container
    private val cardContainer: LinearLayout
    private val posterContainer: FrameLayout
    private val posterImageView: ImageView
    private val fallbackTitleView: TextView
    private val ratingBadge: TextView
    private val seasonBadge: TextView

    // Expandable info panel (slides out on right)
    private val infoPanel: LinearLayout
    private val panelGauge: RatingGaugeView
    private val panelVotesView: TextView
    private val panelTitleView: TextView
    private val panelMetaView: TextView
    private val panelSynopsisView: TextView

    // Focus ring overlay
    private val focusRing: View
    private var widthAnimator: ValueAnimator? = null
    private var isExpanded = false

    init {
        clipChildren = false
        clipToPadding = false
        isFocusable = true
        isClickable = true
        layoutParams = ViewGroup.MarginLayoutParams(restingWidth, cardHeight).apply {
            marginEnd = dp(12)
        }

        // Inner horizontal layout holding poster (left) and info panel (right)
        cardContainer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            clipChildren = true // Clip poster and panel to rounded corners
            clipToPadding = true
            background = GradientDrawable().apply {
                setColor(0xFF141821.toInt())
                cornerRadius = cornerRadiusPx
            }
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        }

        // 1. Poster Container (220x330px / 110x165dp)
        posterContainer = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(posterWidth, cardHeight)
        }

        posterImageView = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        }
        posterContainer.addView(posterImageView)

        // Poster bottom gradient scrim
        val posterScrim = View(context).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.TRANSPARENT, 0xAA090B10.toInt(), 0xEE090B10.toInt())
            )
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, dp(70), Gravity.BOTTOM)
        }
        posterContainer.addView(posterScrim)

        // Fallback title on poster (shown if no poster available)
        fallbackTitleView = TextView(context).apply {
            setTextColor(Color.WHITE)
            textSize = 12f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.BOTTOM or Gravity.START
            setPadding(dp(8), 0, dp(8), dp(8))
            maxLines = 2
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            visibility = View.GONE
        }
        posterContainer.addView(fallbackTitleView)

        // Rating badge on top-right: "★ 7.4"
        ratingBadge = TextView(context).apply {
            setTextColor(Color.WHITE)
            textSize = 9f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply {
                setColor(0xCC090B10.toInt())
                cornerRadius = 4f * density
            }
            setPadding(dp(5), dp(2), dp(5), dp(2))
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END).apply {
                topMargin = dp(6)
                marginEnd = dp(6)
            }
        }
        posterContainer.addView(ratingBadge)

        // Season badge on top-left for series: "S3"
        seasonBadge = TextView(context).apply {
            setTextColor(0xFF42D6C5.toInt())
            textSize = 9f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply {
                setColor(0xCC090B10.toInt())
                cornerRadius = 4f * density
            }
            setPadding(dp(5), dp(2), dp(5), dp(2))
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START).apply {
                topMargin = dp(6)
                marginStart = dp(6)
            }
            visibility = View.GONE
        }
        posterContainer.addView(seasonBadge)

        cardContainer.addView(posterContainer)

        // 2. Info Panel (340px / 170dp width)
        val panelWidth = dp(170)
        infoPanel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(panelWidth, cardHeight)
            visibility = View.GONE
        }

        // Row 1: 52px (26dp) Rating Gauge + TMDB & Vote count
        val gaugeRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        }

        panelGauge = RatingGaugeView(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(26), dp(26))
        }
        gaugeRow.addView(panelGauge)

        val votesCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), 0, 0, 0)
        }
        val tmdbLabel = TextView(context).apply {
            text = "TMDB"
            textSize = 8f
            setTextColor(0xFF8B7CFF.toInt())
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        votesCol.addView(tmdbLabel)

        panelVotesView = TextView(context).apply {
            textSize = 8f
            setTextColor(0xFF929AAA.toInt())
        }
        votesCol.addView(panelVotesView)
        gaugeRow.addView(votesCol)

        infoPanel.addView(gaugeRow)

        // Row 2: Title (28px / 14sp Sora bold)
        panelTitleView = TextView(context).apply {
            textSize = 13f
            setTextColor(Color.WHITE)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, dp(4), 0, 0)
        }
        infoPanel.addView(panelTitleView)

        // Row 3: Metadata (e.g. 2026 · 2h 11m · Sci-Fi)
        panelMetaView = TextView(context).apply {
            textSize = 9f
            setTextColor(0xFF929AAA.toInt())
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, dp(2), 0, 0)
        }
        infoPanel.addView(panelMetaView)

        // Row 4: 3-line synopsis
        panelSynopsisView = TextView(context).apply {
            textSize = 9f
            setTextColor(0xFFC7CCD6.toInt())
            maxLines = 3
            ellipsize = android.text.TextUtils.TruncateAt.END
            lineHeight = dp(13)
            setPadding(0, dp(4), 0, 0)
            layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f)
        }
        infoPanel.addView(panelSynopsisView)

        // Row 5: Action hints: [OK · Watch] (pill) and Hold · More info
        val hintsRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, 0)
        }

        val watchPill = TextView(context).apply {
            text = "OK · Watch"
            textSize = 8f
            setTextColor(0xFF090B10.toInt())
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = 999f
            }
            setPadding(dp(6), dp(2), dp(6), dp(2))
        }
        hintsRow.addView(watchPill)

        val holdHint = TextView(context).apply {
            text = "Hold · More info"
            textSize = 8f
            setTextColor(0xFF929AAA.toInt())
            setPadding(dp(6), 0, 0, 0)
        }
        hintsRow.addView(holdHint)

        infoPanel.addView(hintsRow)
        cardContainer.addView(infoPanel)
        addView(cardContainer)

        // 3. Focus Ring Overlay (4px ring, 5px offset, no scale-up)
        focusRing = View(context).apply {
            val strokeWidth = (2f * density).toInt().coerceAtLeast(4)
            background = GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                setStroke(strokeWidth, 0xFF8B7CFF.toInt())
                cornerRadius = cornerRadiusPx + 4f
            }
            visibility = View.GONE
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        }
        addView(focusRing)

        setupFocusBehavior()
    }

    private fun setupFocusBehavior() {
        setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                currentItem?.let { onCardFocused(it, this) }
                expandCard()
            } else {
                collapseCard()
            }
        }

        setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN) {
                if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                    if (event.isLongPress) {
                        currentItem?.let { onMoreInfoClicked(it, this) }
                        true
                    } else {
                        false // Allow click listener to fire
                    }
                } else if (keyCode == KeyEvent.KEYCODE_INFO || keyCode == KeyEvent.KEYCODE_MENU) {
                    currentItem?.let { onMoreInfoClicked(it, this) }
                    true
                } else false
            } else if (event.action == KeyEvent.ACTION_UP && (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)) {
                if (event.repeatCount == 0 && !event.isCanceled) {
                    currentItem?.let { onWatchClicked(it) }
                    true
                } else false
            } else false
        }

        setOnLongClickListener {
            currentItem?.let { onMoreInfoClicked(it, this) }
            true
        }

        setOnClickListener {
            currentItem?.let { onWatchClicked(it) }
        }
    }

    fun bind(item: TmdbItem, genresStr: String) {
        this.currentItem = item
        ratingBadge.text = "★ ${item.formattedRating}"

        if (item.mediaType == "tv" && item.seasonsCount > 0) {
            seasonBadge.text = "S${item.seasonsCount}"
            seasonBadge.visibility = View.VISIBLE
        } else {
            seasonBadge.visibility = View.GONE
        }

        // Panel binding
        panelGauge.setScore(item.voteAverage)
        panelVotesView.text = item.formattedVotes
        panelTitleView.text = item.title

        val metaParts = mutableListOf<String>()
        if (item.year.isNotBlank()) metaParts.add(item.year)
        if (item.mediaType == "movie" && item.runtimeMinutes > 0) {
            val h = item.runtimeMinutes / 60
            val m = item.runtimeMinutes % 60
            metaParts.add(if (h > 0) "${h}h ${m}m" else "${m}m")
        } else if (item.mediaType == "tv" && item.seasonsCount > 0) {
            metaParts.add("${item.seasonsCount} Seasons")
        }
        if (genresStr.isNotBlank()) metaParts.add(genresStr)
        panelMetaView.text = metaParts.joinToString(" · ")
        panelSynopsisView.text = item.overview

        // Fallback display if poster fails
        fallbackTitleView.text = item.title

        // Load poster image
        posterImageView.setImageDrawable(null)
        if (!item.posterPath.isNullOrBlank()) {
            fallbackTitleView.visibility = View.GONE
            imageLoader.load(item.posterPath, TmdbImageLoader.SIZE_POSTER, posterWidth, cardHeight) { bmp ->
                if (bmp != null) {
                    posterImageView.setImageBitmap(bmp)
                    fallbackTitleView.visibility = View.GONE
                } else {
                    fallbackTitleView.visibility = View.VISIBLE
                }
            }
        } else {
            fallbackTitleView.visibility = View.VISIBLE
        }

        // Preload Palette dominant color for info panel
        if (!isLowRam) {
            imageLoader.extractDominantColor(item.id, item.posterPath) { color ->
                if (isExpanded) {
                    applyPanelBackground(color)
                }
            }
        }
    }

    private fun applyPanelBackground(dominantColor: Int) {
        infoPanel.background = GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(dominantColor, 0xFF141821.toInt())
        )
    }

    private fun expandCard() {
        if (isExpanded) return
        isExpanded = true
        focusRing.visibility = View.VISIBLE
        infoPanel.visibility = View.VISIBLE
        elevation = 8f * density

        val targetColor = if (isLowRam) 0xFF141821.toInt() else (imageLoader.getCachedBitmap("dominant:${currentItem?.id}")?.let { 0 } ?: 0xFF141821.toInt())
        currentItem?.let { item ->
            if (!isLowRam) {
                imageLoader.extractDominantColor(item.id, item.posterPath) { color ->
                    applyPanelBackground(color)
                }
            } else {
                applyPanelBackground(0xFF141821.toInt())
            }
        }

        if (isLowRam) {
            val lp = layoutParams
            lp.width = expandedWidth
            layoutParams = lp
            requestLayout()
        } else {
            widthAnimator?.cancel()
            widthAnimator = ValueAnimator.ofInt(width, expandedWidth).apply {
                duration = 180L
                interpolator = DecelerateInterpolator(1.5f)
                addUpdateListener { anim ->
                    val w = anim.animatedValue as Int
                    val lp = layoutParams
                    lp.width = w
                    layoutParams = lp
                }
                start()
            }
        }
    }

    private fun collapseCard() {
        if (!isExpanded) return
        isExpanded = false
        focusRing.visibility = View.GONE
        elevation = 0f

        if (isLowRam) {
            infoPanel.visibility = View.GONE
            val lp = layoutParams
            lp.width = restingWidth
            layoutParams = lp
            requestLayout()
        } else {
            widthAnimator?.cancel()
            widthAnimator = ValueAnimator.ofInt(width, restingWidth).apply {
                duration = 180L
                interpolator = DecelerateInterpolator(1.5f)
                addUpdateListener { anim ->
                    val w = anim.animatedValue as Int
                    val lp = layoutParams
                    lp.width = w
                    layoutParams = lp
                    if (w <= restingWidth + dp(10)) {
                        infoPanel.visibility = View.GONE
                    }
                }
                start()
            }
        }
    }
}
