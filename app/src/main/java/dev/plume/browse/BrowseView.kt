package dev.plume.browse

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.TransitionDrawable
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import dev.plume.R
import dev.plume.TvFocusHelper
import dev.plume.tmdb.RatingGaugeView
import dev.plume.tmdb.TmdbGenre
import dev.plume.tmdb.TmdbImageLoader
import dev.plume.tmdb.TmdbItem
import dev.plume.tmdb.TmdbRepository

class BrowseView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()

    private lateinit var repository: TmdbRepository
    private lateinit var imageLoader: TmdbImageLoader
    private lateinit var moreInfoOverlay: MoreInfoOverlay
    private var onWatchClicked: ((TmdbItem) -> Unit)? = null
    private var onBackToTabs: (() -> Unit)? = null

    var currentMediaType: String = "movie"
        private set

    // Saved state per mediaType: chipId, sort, scrollY, focusedItem
    private val tabState = mutableMapOf<String, SavedState>()

    private data class SavedState(
        var selectedGenreId: Int = 0,
        var currentSort: String = "popularity.desc",
        var sortLabel: String = "Popular",
        var focusedItemId: Int = 0
    )

    private var currentGenreId: Int = 0
    private var currentSort: String = "popularity.desc"
    private var sortDisplayLabel: String = "Popular"

    // UI elements
    private lateinit var eyebrowView: TextView
    private lateinit var titleView: TextView
    private lateinit var ratingGauge: RatingGaugeView
    private lateinit var metadataView: TextView
    private lateinit var synopsisView: TextView
    private lateinit var watchButton: TextView
    private lateinit var moreInfoButton: TextView
    private lateinit var genreChipsContainer: LinearLayout
    private lateinit var genreChipsScroll: HorizontalScrollView
    private lateinit var sortButton: View
    private lateinit var sortLabelView: TextView
    private lateinit var rowsScrollView: ScrollView
    private lateinit var rowsContainer: LinearLayout
    private lateinit var gridContainer: GridLayout
    private lateinit var emptyStateView: LinearLayout

    // Debounce handler for ambient background image (250ms)
    private val debounceHandler = Handler(Looper.getMainLooper())
    private var pendingBackdropRunnable: Runnable? = null
    private var currentlyFocusedItem: TmdbItem? = null
    private var onAmbientBackdropChanged: ((String?) -> Unit)? = null

    private val isLowTier: Boolean
        get() = dev.plume.DeviceTier.isLowTier(context)

    // Track active genre rows and their pagination
    private val genreRows = mutableMapOf<Int, GenreRowState>()
    private val genreRowsOrder = mutableListOf<GenreRowState>()

    private class GenreRowState(
        val genreId: Int,
        val genreName: String,
        val container: LinearLayout,
        val scroll: HorizontalScrollView,
        var currentPage: Int = 1,
        var isLoading: Boolean = false,
        var hasMorePages: Boolean = true,
        var isLoaded: Boolean = false,
        var placeholderView: View? = null,
        val loadedItemIds: MutableSet<Int> = mutableSetOf()
    )

    init {
        clipChildren = false
        clipToPadding = false
        setupLayout()
    }

    fun initDependencies(
        repository: TmdbRepository,
        imageLoader: TmdbImageLoader,
        moreInfoOverlay: MoreInfoOverlay,
        onWatchClicked: (TmdbItem) -> Unit,
        onBackToTabs: () -> Unit,
        onAmbientBackdropChanged: ((String?) -> Unit)? = null
    ) {
        this.repository = repository
        this.imageLoader = imageLoader
        this.moreInfoOverlay = moreInfoOverlay
        this.onWatchClicked = onWatchClicked
        this.onBackToTabs = onBackToTabs
        this.onAmbientBackdropChanged = onAmbientBackdropChanged
    }

    private fun setupLayout() {
        // Main Content Area (transparent, sits cleanly below top bar)
        val mainContent = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
            setPadding(0, 0, 0, 0)
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        }

        // ================= TOP INFO BLOCK =================
        val infoBlock = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            layoutParams = LinearLayout.LayoutParams(dp(540), LayoutParams.WRAP_CONTENT).apply {
                marginStart = 0
            }
        }

        eyebrowView = TextView(context).apply {
            text = "MOVIES · POPULAR"
            textSize = 10f
            setTextColor(0xFF42D6C5.toInt())
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            letterSpacing = 0.08f
        }
        infoBlock.addView(eyebrowView)

        titleView = TextView(context).apply {
            text = ""
            textSize = 28f // 72px equivalent on 1080p
            setTextColor(Color.WHITE)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, dp(2), 0, 0)
        }
        infoBlock.addView(titleView)

        val ratingRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, 0)
        }

        ratingGauge = RatingGaugeView(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(30), dp(30)) // 60px equivalent
        }
        ratingRow.addView(ratingGauge)

        metadataView = TextView(context).apply {
            text = ""
            textSize = 11f
            setTextColor(0xFF929AAA.toInt())
            setPadding(dp(10), 0, 0, 0)
        }
        ratingRow.addView(metadataView)
        infoBlock.addView(ratingRow)

        synopsisView = TextView(context).apply {
            text = ""
            textSize = 10f
            setTextColor(0xFFC7CCD6.toInt())
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            lineHeight = dp(14)
            setPadding(0, dp(6), 0, 0)
        }
        infoBlock.addView(synopsisView)

        // Buttons: Watch → & More info
        val buttonsRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(10), 0, 0)
        }

        watchButton = TextView(context).apply {
            text = "Watch →"
            textSize = 11f
            setTextColor(Color.WHITE)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply {
                setColor(0x22FFFFFF)
                cornerRadius = 999f
                setStroke(dp(1), 0x55FFFFFF)
            }
            setPadding(dp(18), dp(6), dp(18), dp(6))
            isFocusable = true
            isClickable = true
            setOnClickListener {
                currentlyFocusedItem?.let { item -> onWatchClicked?.invoke(item) }
            }
            setOnKeyListener { _, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN) {
                    when (keyCode) {
                        KeyEvent.KEYCODE_DPAD_DOWN -> {
                            genreChipsContainer.getChildAt(0)?.requestFocus() ?: false
                        }
                        KeyEvent.KEYCODE_DPAD_RIGHT -> {
                            moreInfoButton.requestFocus()
                            true
                        }
                        KeyEvent.KEYCODE_DPAD_UP -> {
                            val act = context as? Activity
                            act?.findViewById<View>(if (currentMediaType == "tv") R.id.tabSeries else R.id.tabMovies)?.requestFocus() ?: false
                        }
                        else -> false
                    }
                } else false
            }
        }
        TvFocusHelper.attach(watchButton, cornerRadiusPx = 999f, isButton = true, isPill = true)
        buttonsRow.addView(watchButton)

        moreInfoButton = TextView(context).apply {
            text = "More info"
            textSize = 11f
            setTextColor(Color.WHITE)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply {
                setColor(0x33141821.toInt())
                cornerRadius = 999f
                setStroke(dp(1), 0x44FFFFFF)
            }
            setPadding(dp(18), dp(6), dp(18), dp(6))
            isFocusable = true
            isClickable = true
            layoutParams = LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                marginStart = dp(10)
            }
            setOnClickListener {
                currentlyFocusedItem?.let { item -> moreInfoOverlay.show(item, it) }
            }
            setOnKeyListener { _, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN) {
                    when (keyCode) {
                        KeyEvent.KEYCODE_DPAD_DOWN -> {
                            genreChipsContainer.getChildAt(0)?.requestFocus() ?: false
                        }
                        KeyEvent.KEYCODE_DPAD_LEFT -> {
                            watchButton.requestFocus()
                            true
                        }
                        KeyEvent.KEYCODE_DPAD_UP -> {
                            val act = context as? Activity
                            act?.findViewById<View>(if (currentMediaType == "tv") R.id.tabSeries else R.id.tabMovies)?.requestFocus() ?: false
                        }
                        else -> false
                    }
                } else false
            }
        }
        TvFocusHelper.attach(moreInfoButton, cornerRadiusPx = 999f, isButton = true, isPill = true)
        buttonsRow.addView(moreInfoButton)

        infoBlock.addView(buttonsRow)
        mainContent.addView(infoBlock)

        // ================= GENRE CHIPS ROW =================
        val chipsAndSortRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = true
            clipToPadding = true
            setPadding(0, dp(14), 0, dp(6))
            layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        }

        // Sort pill: [ ≡ Sort: Popular ⌄ ] anchored on the left
        val sortContainer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true
            isClickable = true
            background = GradientDrawable().apply {
                setColor(0x2A141821.toInt())
                cornerRadius = 999f
                setStroke(dp(1), 0x33FFFFFF)
            }
            setPadding(dp(12), dp(4), dp(12), dp(4))
            layoutParams = LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, dp(32))

            // Sort icon: 3 tapering lines
            val sortIcon = ImageView(context).apply {
                layoutParams = LinearLayout.LayoutParams(dp(13), dp(13))
                setImageResource(R.drawable.ic_sort)
                setColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN)
            }
            addView(sortIcon)

            // Label: "Sort: Popular"
            sortLabelView = TextView(context).apply {
                text = "Sort: $sortDisplayLabel"
                textSize = 11f
                setTextColor(Color.WHITE)
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setPadding(dp(6), 0, dp(6), 0)
            }
            addView(sortLabelView)

            // Chevron down
            val chevron = ImageView(context).apply {
                layoutParams = LinearLayout.LayoutParams(dp(10), dp(10))
                setImageResource(R.drawable.ic_chevron_down)
                setColorFilter(0xFFB0B5C1.toInt(), PorterDuff.Mode.SRC_IN)
            }
            addView(chevron)

            setOnClickListener { showSortMenu(it) }
            setOnKeyListener { _, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN) {
                    when (keyCode) {
                        KeyEvent.KEYCODE_DPAD_RIGHT -> {
                            genreChipsContainer.getChildAt(0)?.requestFocus() ?: false
                        }
                        KeyEvent.KEYCODE_DPAD_UP -> {
                            if (watchButton.visibility == View.VISIBLE) {
                                watchButton.requestFocus()
                            } else {
                                onBackToTabs?.invoke()
                                true
                            }
                        }
                        KeyEvent.KEYCODE_DPAD_DOWN -> {
                            val target = if (gridContainer.visibility == View.VISIBLE) {
                                gridContainer.getChildAt(0)
                            } else {
                                genreRows[0]?.container?.getChildAt(0)
                            }
                            target?.requestFocus() ?: false
                        }
                        else -> false
                    }
                } else false
            }
        }
        sortButton = sortContainer
        TvFocusHelper.attach(sortButton, cornerRadiusPx = 999f, isButton = true, isPill = true)
        chipsAndSortRow.addView(sortButton)

        // Vertical divider: 1dp wide, 18dp high, translucent white
        val divider = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(1), dp(18)).apply {
                marginStart = dp(14)
                marginEnd = dp(14)
                gravity = Gravity.CENTER_VERTICAL
            }
            background = ColorDrawable(0x33FFFFFF)
        }
        chipsAndSortRow.addView(divider)

        // Horizontally scrolling genre chips starting after divider
        genreChipsScroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            clipChildren = true
            clipToPadding = true
            layoutParams = LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        }

        genreChipsContainer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            clipChildren = true
            clipToPadding = true
            setPadding(0, dp(4), dp(48), dp(4))
        }
        genreChipsScroll.addView(genreChipsContainer)
        chipsAndSortRow.addView(genreChipsScroll)

        mainContent.addView(chipsAndSortRow)

        // ================= CONTENT: ROWS CONTAINER OR GRID =================
        rowsScrollView = ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            clipChildren = false
            clipToPadding = false
            layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        }

        val scrollContent = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
            setPadding(dp(12), dp(4), dp(12), dp(32))
        }

        rowsContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
            layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        }
        scrollContent.addView(rowsContainer)

        // 6-column grid for specific genre selection
        gridContainer = GridLayout(context).apply {
            columnCount = 6
            clipChildren = false
            clipToPadding = false
            layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
            visibility = View.GONE
        }
        scrollContent.addView(gridContainer)

        // Friendly Empty / No Token State
        emptyStateView = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(32), dp(64), dp(32), dp(64))
            layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
            visibility = View.GONE
        }
        val emptyTitle = TextView(context).apply {
            text = "Add a TMDB Read Token in Settings"
            textSize = 16f
            setTextColor(Color.WHITE)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        emptyStateView.addView(emptyTitle)

        val emptyDesc = TextView(context).apply {
            text = "To browse popular movies and series rated above 6.5, enter your free TMDB API token in Plume Settings › Content."
            textSize = 12f
            setTextColor(0xFF929AAA.toInt())
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, 0)
        }
        emptyStateView.addView(emptyDesc)
        scrollContent.addView(emptyStateView)

        rowsScrollView.addView(scrollContent)
        mainContent.addView(rowsScrollView)

        addView(mainContent)
    }

    fun getTopFocusable(): View? {
        return if (watchButton.visibility == View.VISIBLE) watchButton else sortButton
    }

    fun setMediaType(mediaType: String) {
        if (this.currentMediaType == mediaType && genreRows.isNotEmpty()) {
            return
        }

        // Save previous tab state
        tabState[this.currentMediaType] = SavedState(
            selectedGenreId = currentGenreId,
            currentSort = currentSort,
            sortLabel = sortDisplayLabel,
            focusedItemId = currentlyFocusedItem?.id ?: 0
        )

        this.currentMediaType = mediaType

        // Restore tab state if exists
        val saved = tabState[mediaType]
        if (saved != null) {
            currentGenreId = saved.selectedGenreId
            currentSort = saved.currentSort
            sortDisplayLabel = saved.sortLabel
        } else {
            currentGenreId = 0
            currentSort = "popularity.desc"
            sortDisplayLabel = "Popular"
        }

        sortLabelView.text = "Sort: $sortDisplayLabel"
        eyebrowView.text = if (mediaType == "tv") "SERIES · POPULAR" else "MOVIES · POPULAR"

        loadBrowseContent()
    }

    private fun loadBrowseContent() {
        genreRows.clear()
        rowsContainer.removeAllViews()
        gridContainer.removeAllViews()
        genreChipsContainer.removeAllViews()

        // Fetch genres for this media type
        repository.fetchGenres(currentMediaType) { genres ->
            populateGenreChips(genres)
            if (currentGenreId == 0) {
                showAllGenresRows(genres)
            } else {
                showGenreGrid(currentGenreId)
            }
        }
    }

    private fun populateGenreChips(genres: List<TmdbGenre>) {
        genreChipsContainer.removeAllViews()

        // "All genres" chip (id = 0)
        addGenreChip(0, "All genres", isSelected = currentGenreId == 0)

        // Chips for each genre
        for (genre in genres) {
            addGenreChip(genre.id, genre.name, isSelected = currentGenreId == genre.id)
        }
    }

    private fun addGenreChip(id: Int, name: String, isSelected: Boolean) {
        val chip = TextView(context).apply {
            text = name
            textSize = 11f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            isFocusable = true
            isClickable = true

            if (isSelected) {
                background = GradientDrawable().apply {
                    setColor(Color.WHITE)
                    cornerRadius = 999f
                }
                setTextColor(0xFF090B10.toInt())
            } else {
                background = GradientDrawable().apply {
                    setColor(0x2A1A202C.toInt())
                    cornerRadius = 999f
                    setStroke(dp(1), 0x33FFFFFF)
                }
                setTextColor(0xFFE2E6EF.toInt())
            }

            gravity = Gravity.CENTER
            setPadding(dp(16), dp(5), dp(16), dp(5))
            layoutParams = LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, dp(32)).apply {
                marginEnd = dp(8)
            }

            setOnClickListener {
                if (currentGenreId != id) {
                    currentGenreId = id
                    // Refresh chips selection
                    for (i in 0 until genreChipsContainer.childCount) {
                        val child = genreChipsContainer.getChildAt(i) as? TextView ?: continue
                        val isCurrent = child == it
                        child.background = GradientDrawable().apply {
                            setColor(if (isCurrent) Color.WHITE else 0x2A1A202C.toInt())
                            cornerRadius = 999f
                            if (!isCurrent) setStroke(dp(1), 0x33FFFFFF)
                        }
                        child.setTextColor(if (isCurrent) 0xFF090B10.toInt() else 0xFFE2E6EF.toInt())
                    }

                    if (id == 0) {
                        gridContainer.visibility = View.GONE
                        rowsContainer.visibility = View.VISIBLE
                    } else {
                        rowsContainer.visibility = View.GONE
                        gridContainer.visibility = View.VISIBLE
                        showGenreGrid(id)
                    }
                }
            }
            setOnKeyListener { v, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN) {
                    when (keyCode) {
                        KeyEvent.KEYCODE_DPAD_DOWN -> {
                            val target = if (gridContainer.visibility == View.VISIBLE) {
                                gridContainer.getChildAt(0)
                            } else {
                                genreRows[0]?.container?.getChildAt(0)
                            }
                            target?.requestFocus() ?: false
                        }
                        KeyEvent.KEYCODE_DPAD_UP -> {
                            if (watchButton.visibility == View.VISIBLE) {
                                watchButton.requestFocus()
                            } else {
                                onBackToTabs?.invoke()
                                true
                            }
                        }
                        KeyEvent.KEYCODE_DPAD_LEFT -> {
                            val idx = genreChipsContainer.indexOfChild(v)
                            if (idx == 0) {
                                sortButton.requestFocus()
                                true
                            } else false
                        }
                        else -> false
                    }
                } else false
            }
        }
        TvFocusHelper.attach(chip, cornerRadiusPx = 999f, isButton = false, isPill = true)
        genreChipsContainer.addView(chip)
    }

    private fun showAllGenresRows(genres: List<TmdbGenre>) {
        rowsContainer.visibility = View.VISIBLE
        gridContainer.visibility = View.GONE
        rowsContainer.removeAllViews()
        genreRows.clear()
        genreRowsOrder.clear()

        // 1. "Popular now" row (genreId = 0)
        createGenreRow(0, "Popular now")

        // 2. Row per genre
        for (genre in genres) {
            createGenreRow(genre.id, genre.name)
        }

        if (isLowTier) {
            // Load only the first 2 rows upfront on low tier (Popular now + first genre)
            genreRowsOrder.take(2).forEach { state ->
                loadRowPage(state, 1)
            }
        }
    }

    private fun preloadNearbyRows(state: GenreRowState) {
        val idx = genreRowsOrder.indexOf(state)
        if (idx >= 0) {
            for (step in 1..2) {
                if (idx + step < genreRowsOrder.size) {
                    val next = genreRowsOrder[idx + step]
                    if (!next.isLoaded && !next.isLoading) {
                        loadRowPage(next, 1)
                    }
                }
            }
        }
    }

    private fun createGenreRow(genreId: Int, genreName: String) {
        val rowBlock = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
            setPadding(0, dp(12), 0, dp(16))
            layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        }

        // Header: Genre Name (30px / 15sp Sora 600) + "Rated above 6.5 on TMDB"
        val headerRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(8))
        }

        val titleTv = TextView(context).apply {
            text = genreName
            textSize = 15f
            setTextColor(Color.WHITE)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        headerRow.addView(titleTv)

        val subtitleTv = TextView(context).apply {
            text = "Rated above 6.5 on TMDB"
            textSize = 10f
            setTextColor(0xFF929AAA.toInt())
            setPadding(dp(10), 0, 0, 0)
        }
        headerRow.addView(subtitleTv)
        rowBlock.addView(headerRow)

        // Horizontal Scroll
        val scroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            clipChildren = false
            clipToPadding = false
            layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        }

        val cardsRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            clipChildren = false
            clipToPadding = false
            setPadding(0, dp(4), dp(60), dp(4))
        }
        scroll.addView(cardsRow)
        rowBlock.addView(scroll)
        rowsContainer.addView(rowBlock)

        val rowState = GenreRowState(genreId, genreName, cardsRow, scroll)
        genreRows[genreId] = rowState
        genreRowsOrder.add(rowState)

        if (isLowTier) {
            // Lightweight placeholder card so D-pad navigation has a focusable target
            val placeholder = FrameLayout(context).apply {
                layoutParams = LinearLayout.LayoutParams(dp(110), dp(165)).apply {
                    marginEnd = dp(14)
                }
                background = GradientDrawable().apply {
                    setColor(0x1AFFFFFF)
                    cornerRadius = dp(12).toFloat()
                }
                isFocusable = true
                isClickable = true
                setOnFocusChangeListener { _, hasFocus ->
                    if (hasFocus) {
                        loadRowPage(rowState, 1)
                        preloadNearbyRows(rowState)
                    }
                }
            }
            rowState.placeholderView = placeholder
            cardsRow.addView(placeholder)
        } else {
            // Load first page for Popular row immediately; other rows load lazily or in sequence
            loadRowPage(rowState, 1)
        }
    }

    private fun loadRowPage(state: GenreRowState, page: Int) {
        if (state.isLoading || !state.hasMorePages) return
        state.isLoading = true

        repository.getBrowseRow(
            mediaType = currentMediaType,
            genreId = state.genreId,
            sort = currentSort,
            page = page
        ) { items ->
            state.isLoading = false
            state.isLoaded = true
            if (items.isEmpty()) {
                state.hasMorePages = false
                if (state.genreId == 0 && state.cardsRowEmpty) {
                    emptyStateView.visibility = View.VISIBLE
                }
                state.placeholderView?.let {
                    state.container.removeView(it)
                    state.placeholderView = null
                }
                return@getBrowseRow
            }

            emptyStateView.visibility = View.GONE
            state.currentPage = page

            val hadFocusOnPlaceholder = state.placeholderView?.hasFocus() == true
            state.placeholderView?.let {
                state.container.removeView(it)
                state.placeholderView = null
            }

            val genresStr = repository.getGenreName(state.genreId, currentMediaType)
            val itemsToBind = if (isLowTier && page == 1) items.take(8) else items
            var firstCard: View? = null

            for ((index, item) in itemsToBind.withIndex()) {
                if (state.loadedItemIds.contains(item.id)) continue
                state.loadedItemIds.add(item.id)

                val card = ExpandableCardView(
                    context = context,
                    imageLoader = imageLoader,
                    onWatchClicked = { clickedItem -> onWatchClicked?.invoke(clickedItem) },
                    onMoreInfoClicked = { clickedItem, v -> moreInfoOverlay.show(clickedItem, v) },
                    onCardFocused = { focusedItem, view ->
                        onItemFocused(focusedItem, genresStr, view)
                        if (isLowTier) {
                            preloadNearbyRows(state)
                        }
                        // Paging trigger: when within 3 tiles of row end, load next page
                        val totalCards = state.container.childCount
                        val cardIdx = state.container.indexOfChild(view)
                        if (totalCards > 0 && cardIdx >= totalCards - 3) {
                            loadRowPage(state, state.currentPage + 1)
                        }
                    }
                )
                if (state.genreId == 0) {
                    card.setOnKeyListener { _, keyCode, event ->
                        if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                            val activeChip = (0 until genreChipsContainer.childCount)
                                .mapNotNull { genreChipsContainer.getChildAt(it) }
                                .firstOrNull { it.isFocusable }
                            activeChip?.requestFocus() ?: false
                        } else false
                    }
                }
                card.bind(item, genresStr)
                state.container.addView(card)
                if (firstCard == null) firstCard = card

                // If this is the very first card loaded on the page, set it as initial focus
                if (currentlyFocusedItem == null && state.genreId == 0 && index == 0) {
                    onItemFocused(item, genresStr, card)
                }
            }

            if (hadFocusOnPlaceholder) {
                firstCard?.requestFocus()
            }
        }
    }

    private val GenreRowState.cardsRowEmpty: Boolean
        get() = container.childCount == 0

    private fun showGenreGrid(genreId: Int) {
        gridContainer.removeAllViews()
        val genresStr = repository.getGenreName(genreId, currentMediaType)

        repository.getBrowseRow(
            mediaType = currentMediaType,
            genreId = genreId,
            sort = currentSort,
            page = 1
        ) { items ->
            if (items.isEmpty()) {
                emptyStateView.visibility = View.VISIBLE
                return@getBrowseRow
            }
            emptyStateView.visibility = View.GONE

            for ((index, item) in items.withIndex()) {
                val card = ExpandableCardView(
                    context = context,
                    imageLoader = imageLoader,
                    onWatchClicked = { clickedItem -> onWatchClicked?.invoke(clickedItem) },
                    onMoreInfoClicked = { clickedItem, v -> moreInfoOverlay.show(clickedItem, v) },
                    onCardFocused = { focusedItem, view -> onItemFocused(focusedItem, genresStr, view) }
                )
                if (index < 6) {
                    card.setOnKeyListener { _, keyCode, event ->
                        if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                            val activeChip = (0 until genreChipsContainer.childCount)
                                .mapNotNull { genreChipsContainer.getChildAt(it) }
                                .firstOrNull { it.isFocusable }
                            activeChip?.requestFocus() ?: false
                        } else false
                    }
                }
                card.bind(item, genresStr)
                gridContainer.addView(card)
            }
        }
    }

    private fun onItemFocused(item: TmdbItem, genreName: String, originatingCard: View) {
        this.currentlyFocusedItem = item

        // Update Info Block
        val typeLabel = if (item.mediaType == "tv") "SERIES" else "MOVIES"
        val genreLabel = if (genreName.isNotBlank()) " · ${genreName.uppercase()}" else ""
        eyebrowView.text = "$typeLabel$genreLabel"
        titleView.text = item.title
        ratingGauge.setScore(item.voteAverage)

        val metaParts = mutableListOf<String>()
        if (item.year.isNotBlank()) metaParts.add(item.year)
        if (item.mediaType == "tv" && item.seasonsCount > 0) metaParts.add("${item.seasonsCount} Seasons")
        else if (item.mediaType == "movie" && item.runtimeMinutes > 0) {
            val h = item.runtimeMinutes / 60
            val m = item.runtimeMinutes % 60
            metaParts.add(if (h > 0) "${h}h ${m}m" else "${m}m")
        }
        if (genreName.isNotBlank()) metaParts.add(genreName)
        metadataView.text = metaParts.joinToString(" · ")
        synopsisView.text = item.overview

        // Debounced Ambient Backdrop Loader (250ms debounce)
        pendingBackdropRunnable?.let { debounceHandler.removeCallbacks(it) }
        val runnable = Runnable {
            if (!item.backdropPath.isNullOrBlank()) {
                onAmbientBackdropChanged?.invoke(item.backdropPath)
            }
        }
        pendingBackdropRunnable = runnable
        debounceHandler.postDelayed(runnable, 250L)

        // Prefetch next 3 tiles in this row
        val parentRow = originatingCard.parent as? ViewGroup
        if (parentRow != null) {
            val idx = parentRow.indexOfChild(originatingCard)
            for (i in 1..3) {
                val nextCard = parentRow.getChildAt(idx + i) as? ExpandableCardView
                nextCard?.currentItem?.let { nextItem ->
                    imageLoader.prefetch(nextItem.posterPath, TmdbImageLoader.SIZE_POSTER)
                }
            }
        }
    }

    private fun showSortMenu(anchor: View) {
        val popup = PopupMenu(context, anchor)
        popup.menu.add(0, 1, 0, "Popular")
        popup.menu.add(0, 2, 1, "Top rated")
        popup.menu.add(0, 3, 2, "Newest")

        popup.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                1 -> {
                    currentSort = "popularity.desc"
                    sortDisplayLabel = "Popular"
                }
                2 -> {
                    currentSort = "vote_average.desc"
                    sortDisplayLabel = "Top rated"
                }
                3 -> {
                    currentSort = if (currentMediaType == "tv") "first_air_date.desc" else "primary_release_date.desc"
                    sortDisplayLabel = "Newest"
                }
            }
            sortLabelView.text = "Sort: $sortDisplayLabel"
            loadBrowseContent()
            true
        }
        popup.show()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                if (currentGenreId != 0) {
                    // Back from grid returns to "All genres"
                    currentGenreId = 0
                    populateGenreChips(emptyList()) // reselects all genres
                    gridContainer.visibility = View.GONE
                    rowsContainer.visibility = View.VISIBLE
                    return true
                } else {
                    // Back from rows returns focus to tabs
                    onBackToTabs?.invoke()
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }
}
