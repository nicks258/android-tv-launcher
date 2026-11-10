package dev.plume.livetv

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.speech.RecognizerIntent
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import dev.plume.R
import dev.plume.TvFocusHelper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LiveTvGuideView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    // 1920x1080 design scaling: px(designPx) returns exact layout pixels
    private val screenW: Float get() = resources.displayMetrics.widthPixels.toFloat()
    private fun px(designPx: Int): Int = ((designPx * screenW) / 1920f).toInt()
    private fun px(designPx: Float): Float = (designPx * screenW) / 1920f

    private val repository = LiveTvRepository(context)
    private val prefs: SharedPreferences = context.getSharedPreferences("plume_livetv_prefs", Context.MODE_PRIVATE)

    var onBackToTopBar: (() -> Unit)? = null

    // State
    private var currentCountryCode: String = prefs.getString("selected_country", "") ?: ""
    private var currentCountryName: String = "All Channels"
    private var searchQuery: String = ""
    private var countriesList: List<CountryItem> = emptyList()
    private var channelsList: List<ChannelItem> = emptyList()
    private var programmesMap: Map<String, List<ProgrammeItem>> = emptyMap()
    private var renderedChannelCount = 0
    private var lastAirNow: Long = 0L

    // Timeline base slots
    private var timelineStartMs: Long = 0L
    private val slotWidthPx: Int get() = px(371) // ~371px per 30 minutes in 1920 design
    private val halfHourMs = 1800000L

    // Debounce handlers
    private val mainHandler = Handler(Looper.getMainLooper())
    private var pendingPreviewUpdate: Runnable? = null
    private var nowLineTicker: Runnable? = null
    private var searchDebounceRunnable: Runnable? = null

    // Track focused channel and programme
    private var focusedChannel: ChannelItem? = null
    private var focusedProgramme: ProgrammeItem? = null
    private var lastWatchedChannelId: String = "104"

    // UI Components
    private lateinit var contentContainer: LinearLayout

    // Top Preview Views
    private lateinit var previewCard: FrameLayout
    private lateinit var previewBadge: TextView
    private lateinit var previewLogoText: TextView
    private lateinit var previewTagView: TextView
    private lateinit var previewTitleView: TextView
    private lateinit var previewMetaView: TextView
    private lateinit var previewProgressBarContainer: FrameLayout
    private lateinit var previewProgressBarFill: View
    private lateinit var previewNextView: TextView

    // Search and Country Views
    private lateinit var searchBoxView: LinearLayout
    private lateinit var searchEditText: EditText
    private lateinit var clearIcon: ImageView
    private lateinit var countryChipsScroll: HorizontalScrollView
    private lateinit var countryChipsContainer: LinearLayout

    // Grid Views
    private lateinit var gridCountryHeader: TextView
    private lateinit var gridTimelineHeaderScroll: HorizontalScrollView
    private lateinit var gridTimelineHeaderContainer: LinearLayout
    private lateinit var gridVerticalScroll: ScrollView
    private lateinit var gridTimelineScroll: HorizontalScrollView
    private lateinit var channelsColumnContainer: LinearLayout
    private lateinit var programmesMatrixContainer: LinearLayout
    private lateinit var nowLineOverlay: NowLineOverlayView

    // Error / Empty State Views
    private lateinit var errorStateContainer: LinearLayout
    private lateinit var errorTitleView: TextView
    private lateinit var errorSubtitleView: TextView
    private lateinit var errorActionButton: TextView

    init {
        initLayout()
    }

    private fun initLayout() {
        setBackgroundColor(Color.parseColor("#090B10"))
        clipChildren = false
        clipToPadding = false

        contentContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT).apply {
                setMargins(0, px(8), 0, px(16))
            }
            clipChildren = false
            clipToPadding = false
        }
        addView(contentContainer)

        setupPreviewArea()
        setupSearchAndCountryRow()
        setupGuideGrid()
        setupErrorState()
    }

    // =========================================================================
    // 1. TOP PREVIEW AREA (y=150px, height=340px)
    // =========================================================================
    private fun setupPreviewArea() {
        val previewRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, px(340)).apply {
                bottomMargin = px(24)
            }
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            clipToPadding = false
        }

        // Left Preview Card (604×340px, radius 18px)
        previewCard = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(px(604), px(340)).apply {
                rightMargin = px(48)
            }
            isFocusable = true
            isFocusableInTouchMode = true
            clipToOutline = true

            val cardBg = GradientDrawable().apply {
                gradientType = GradientDrawable.RADIAL_GRADIENT
                colors = intArrayOf(Color.parseColor("#0D555A"), Color.parseColor("#0A161D"))
                gradientRadius = px(380).toFloat()
                setGradientCenter(0.45f, 0.45f)
                cornerRadius = px(18).toFloat()
            }
            background = cardBg

            TvFocusHelper.attach(this, cornerRadiusPx = px(18).toFloat(), isButton = true)

            setOnClickListener {
                focusedChannel?.let { ch ->
                    launchOnyxChannel(ch.channelId, ch.name, focusedProgramme?.start ?: 0L, focusedProgramme?.hasCatchup == true)
                }
            }

            setOnKeyListener { _, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN) {
                    when (keyCode) {
                        KeyEvent.KEYCODE_DPAD_UP -> {
                            onBackToTopBar?.invoke()
                            true
                        }
                        KeyEvent.KEYCODE_DPAD_DOWN -> {
                            searchEditText.requestFocus()
                            true
                        }
                        else -> false
                    }
                } else false
            }
        }

        // Ambient Preview Card - Logo Text hidden by default to match clean ambient design
        previewLogoText = TextView(context).apply {
            layoutParams = FrameLayout.LayoutParams(px(80), px(80)).apply {
                gravity = Gravity.CENTER
            }
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_PX, px(28).toFloat())
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            visibility = View.GONE
        }
        previewCard.addView(previewLogoText)

        // ● LIVE Badge (Top Left of preview card: 18px text, weight 800)
        previewBadge = TextView(context).apply {
            layoutParams = FrameLayout.LayoutParams(LayoutParams.WRAP_CONTENT, px(32)).apply {
                gravity = Gravity.TOP or Gravity.START
                setMargins(px(20), px(20), 0, 0)
            }
            setPadding(px(14), 0, px(14), 0)
            gravity = Gravity.CENTER
            text = "● LIVE"
            setTextSize(TypedValue.COMPLEX_UNIT_PX, px(16).toFloat())
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            letterSpacing = 0.1f
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#FF5A6E"))
                cornerRadius = px(16).toFloat()
            }
            visibility = View.VISIBLE
        }
        previewCard.addView(previewBadge)

        previewRow.addView(previewCard)

        // Right Preview Metadata
        val metaContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
            gravity = Gravity.CENTER_VERTICAL
        }

        // Line 1: CH 104 · OCEANIC ONE (22px, #42D6C5, uppercase, letter-spacing 0.12em)
        previewTagView = TextView(context).apply {
            layoutParams = LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = px(10)
            }
            setTextSize(TypedValue.COMPLEX_UNIT_PX, px(22).toFloat())
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#42D6C5"))
            letterSpacing = 0.12f
            text = "CH 104 · OCEANIC ONE"
        }
        metaContainer.addView(previewTagView)

        // Line 2: Programme Title (56px Sora 700)
        previewTitleView = TextView(context).apply {
            layoutParams = LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = px(12)
            }
            setTextSize(TypedValue.COMPLEX_UNIT_PX, px(52).toFloat())
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            text = "Deep Currents"
        }
        metaContainer.addView(previewTitleView)

        // Line 3: 21:30 - 22:30 · Documentary (22px, #C7CCD6)
        previewMetaView = TextView(context).apply {
            layoutParams = LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = px(16)
            }
            setTextSize(TypedValue.COMPLEX_UNIT_PX, px(22).toFloat())
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(Color.parseColor("#C7CCD6"))
            text = "21:30 – 22:30 · Documentary"
        }
        metaContainer.addView(previewMetaView)

        // Line 4: Progress Bar (520×6px, radius 3px)
        previewProgressBarContainer = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(px(520), px(6)).apply {
                bottomMargin = px(16)
            }
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#2E3440"))
                cornerRadius = px(3).toFloat()
            }
        }
        previewProgressBarFill = View(context).apply {
            layoutParams = FrameLayout.LayoutParams(px(220), LayoutParams.MATCH_PARENT)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#8B7CFF"))
                cornerRadius = px(3).toFloat()
            }
        }
        previewProgressBarContainer.addView(previewProgressBarFill)
        metaContainer.addView(previewProgressBarContainer)

        // Line 5: Next · 22:30 · Reef Nights (22px, #929AAA)
        previewNextView = TextView(context).apply {
            layoutParams = LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, px(22).toFloat())
            typeface = Typeface.DEFAULT
            setTextColor(Color.parseColor("#929AAA"))
            text = "Next · 22:30 · Reef Nights"
        }
        metaContainer.addView(previewNextView)

        previewRow.addView(metaContainer)
        contentContainer.addView(previewRow)
    }

    // =========================================================================
    // 2. SEARCH & COUNTRY ROW (height: 48px, top: 530px)
    // =========================================================================
    private fun setupSearchAndCountryRow() {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, px(48)).apply {
                bottomMargin = px(14)
            }
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            clipToPadding = false
        }

        val normalBg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(Color.parseColor("#0FFFFFFF"))
            setStroke(px(2), Color.parseColor("#29FFFFFF"))
            cornerRadius = px(24).toFloat()
        }
        val focusedBg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(Color.parseColor("#26FFFFFF"))
            setStroke(px(2), Color.parseColor("#8B7CFF"))
            cornerRadius = px(24).toFloat()
        }

        // Search Box (324px wide matching channel column below)
        searchBoxView = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(px(324), px(48)).apply {
                rightMargin = px(16)
            }
            gravity = Gravity.CENTER_VERTICAL
            setPadding(px(14), 0, px(14), 0)
            isFocusable = false
            background = normalBg

            setOnClickListener {
                searchEditText.requestFocus()
                val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                imm?.showSoftInput(searchEditText, InputMethodManager.SHOW_IMPLICIT)
            }
        }

        val searchIcon = ImageView(context).apply {
            layoutParams = LinearLayout.LayoutParams(px(20), px(20)).apply {
                rightMargin = px(8)
            }
            setImageResource(R.drawable.ic_search)
            setColorFilter(Color.parseColor("#929AAA"))
        }
        searchBoxView.addView(searchIcon)

        searchEditText = EditText(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f).apply {
                setMargins(px(4), 0, px(4), 0)
            }
            background = null
            setPadding(0, 0, 0, 0)
            gravity = Gravity.CENTER_VERTICAL
            setTextSize(TypedValue.COMPLEX_UNIT_PX, px(18).toFloat())
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#929AAA"))
            hint = "Search channels"
            isSingleLine = true
            maxLines = 1
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_ACTION_SEARCH or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            isFocusable = true
            isFocusableInTouchMode = true
            isCursorVisible = true

            setOnFocusChangeListener { _, hasFocus ->
                searchBoxView.background = if (hasFocus) focusedBg else normalBg
                searchIcon.setColorFilter(if (hasFocus) Color.parseColor("#8B7CFF") else Color.parseColor("#929AAA"))
                if (hasFocus) {
                    val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                    imm?.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
                }
            }

            setOnKeyListener { _, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN) {
                    when (keyCode) {
                        KeyEvent.KEYCODE_DPAD_UP -> {
                            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                            imm?.hideSoftInputFromWindow(windowToken, 0)
                            onBackToTopBar?.invoke()
                            true
                        }
                        KeyEvent.KEYCODE_DPAD_DOWN -> {
                            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                            imm?.hideSoftInputFromWindow(windowToken, 0)
                            if (channelsColumnContainer.childCount > 0) {
                                channelsColumnContainer.getChildAt(0).requestFocus()
                                true
                            } else if (errorActionButton.visibility == View.VISIBLE) {
                                errorActionButton.requestFocus()
                                true
                            } else false
                        }
                        KeyEvent.KEYCODE_DPAD_RIGHT -> {
                            if (selectionEnd >= text.length) {
                                val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                                imm?.hideSoftInputFromWindow(windowToken, 0)
                                if (countryChipsContainer.childCount > 0) {
                                    countryChipsContainer.getChildAt(0).requestFocus()
                                    true
                                } else false
                            } else false
                        }
                        KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_CENTER -> {
                            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                            imm?.showSoftInput(this, InputMethodManager.SHOW_FORCED)
                            false
                        }
                        else -> false
                    }
                } else false
            }

            setOnEditorActionListener { _, actionId, event ->
                if (actionId == EditorInfo.IME_ACTION_SEARCH ||
                    actionId == EditorInfo.IME_ACTION_DONE ||
                    (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
                ) {
                    val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                    imm?.hideSoftInputFromWindow(windowToken, 0)
                    clearFocus()
                    if (channelsColumnContainer.childCount > 0) {
                        channelsColumnContainer.getChildAt(0).requestFocus()
                    } else if (errorActionButton.visibility == View.VISIBLE) {
                        errorActionButton.requestFocus()
                    }
                    true
                } else {
                    false
                }
            }

            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    val query = s?.toString()?.trim() ?: ""
                    clearIcon.visibility = if (query.isNotEmpty()) View.VISIBLE else View.GONE

                    searchDebounceRunnable?.let { removeCallbacks(it) }
                    val r = Runnable {
                        if (searchQuery != query) {
                            searchQuery = query
                            loadChannelsAndProgrammes()
                        }
                    }
                    searchDebounceRunnable = r
                    postDelayed(r, 300L)
                }
                override fun afterTextChanged(s: Editable?) {}
            })
        }
        searchBoxView.addView(searchEditText)

        clearIcon = ImageView(context).apply {
            layoutParams = LinearLayout.LayoutParams(px(20), px(20)).apply {
                rightMargin = px(8)
            }
            setImageResource(R.drawable.ic_clear)
            setColorFilter(Color.parseColor("#929AAA"))
            visibility = View.GONE
            setOnClickListener {
                searchEditText.setText("")
                searchEditText.requestFocus()
            }
        }
        searchBoxView.addView(clearIcon)

        val micIcon = ImageView(context).apply {
            layoutParams = LinearLayout.LayoutParams(px(20), px(20))
            setImageResource(R.drawable.ic_mic)
            setColorFilter(Color.parseColor("#929AAA"))
            setOnClickListener { launchVoiceSearch() }
        }
        searchBoxView.addView(micIcon)

        row.addView(searchBoxView)

        // Country Chips List (Starting at x=340px)
        countryChipsScroll = HorizontalScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, px(48))
            isHorizontalScrollBarEnabled = false
            clipChildren = false
            clipToPadding = false
        }

        countryChipsContainer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = FrameLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT)
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            clipToPadding = false
        }
        countryChipsScroll.addView(countryChipsContainer)
        row.addView(countryChipsScroll)

        contentContainer.addView(row)
    }

    // =========================================================================
    // 3. GUIDE GRID (Header + Channel Rows + Now Line)
    // =========================================================================
    private fun setupGuideGrid() {
        val gridSection = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f)
            clipChildren = false
            clipToPadding = false
        }

        // Header Row (36px tall): Country Name + 30-min Time Slots
        val headerRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, px(36)).apply {
                bottomMargin = px(8)
            }
            gravity = Gravity.CENTER_VERTICAL
        }

        // Country Name (340px wide, 20px font, weight 700, white)
        gridCountryHeader = TextView(context).apply {
            layoutParams = LinearLayout.LayoutParams(px(340), LayoutParams.WRAP_CONTENT)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, px(20).toFloat())
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            text = currentCountryName
        }
        headerRow.addView(gridCountryHeader)

        // Timeline Time Markers Scroll (Synchronized horizontally)
        gridTimelineHeaderScroll = HorizontalScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            isHorizontalScrollBarEnabled = false
            isFocusable = false
            clipChildren = true
            clipToPadding = true
        }

        gridTimelineHeaderContainer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = FrameLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT)
            gravity = Gravity.CENTER_VERTICAL
        }
        gridTimelineHeaderScroll.addView(gridTimelineHeaderContainer)
        headerRow.addView(gridTimelineHeaderScroll)

        gridSection.addView(headerRow)

        // Grid Content Frame containing Channel Column + Horizontally Scrollable Programme Matrix
        val gridFrame = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f)
            clipChildren = false
            clipToPadding = false
        }

        gridVerticalScroll = ScrollView(context).apply {
            layoutParams = FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            isVerticalScrollBarEnabled = false
            clipChildren = false
            clipToPadding = false
        }

        val rowsHorizontalWrapper = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
            clipChildren = false
            clipToPadding = false
        }

        // Left fixed channels column (340px wide)
        channelsColumnContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(px(340), LayoutParams.WRAP_CONTENT)
            clipChildren = false
            clipToPadding = false
        }
        rowsHorizontalWrapper.addView(channelsColumnContainer)

        // Right scrollable programme matrix
        gridTimelineScroll = HorizontalScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
            isHorizontalScrollBarEnabled = false
            clipChildren = true
            clipToPadding = true
            setOnScrollChangeListener { _, scrollX, _, _, _ ->
                gridTimelineHeaderScroll.scrollTo(scrollX, 0)
                nowLineOverlay.setScrollXOffset(scrollX)
            }
        }

        programmesMatrixContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
            clipChildren = false
            clipToPadding = false
        }
        gridTimelineScroll.addView(programmesMatrixContainer)
        rowsHorizontalWrapper.addView(gridTimelineScroll)

        gridVerticalScroll.addView(rowsHorizontalWrapper)
        gridFrame.addView(gridVerticalScroll)

        // Now Line Overlay (3px #FF5A6E vertical timeline line)
        nowLineOverlay = NowLineOverlayView(context).apply {
            layoutParams = FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        }
        gridFrame.addView(nowLineOverlay)

        gridSection.addView(gridFrame)
        contentContainer.addView(gridSection)
    }

    // =========================================================================
    // 4. ERROR & EMPTY STATE VIEW
    // =========================================================================
    private fun setupErrorState() {
        errorStateContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            gravity = Gravity.CENTER
            visibility = View.GONE
        }

        errorTitleView = TextView(context).apply {
            layoutParams = LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = px(12)
            }
            setTextSize(TypedValue.COMPLEX_UNIT_PX, px(32).toFloat())
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            text = "Live TV Unavailable"
        }
        errorStateContainer.addView(errorTitleView)

        errorSubtitleView = TextView(context).apply {
            layoutParams = LinearLayout.LayoutParams(px(480), LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = px(24)
            }
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_PX, px(20).toFloat())
            setTextColor(Color.parseColor("#929AAA"))
            text = "Please set up your IPTV playlist in Onyx TV."
        }
        errorStateContainer.addView(errorSubtitleView)

        errorActionButton = TextView(context).apply {
            layoutParams = LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, px(48))
            setPadding(px(28), 0, px(28), 0)
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_PX, px(18).toFloat())
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.BLACK)
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = px(24).toFloat()
            }
            isFocusable = true
            TvFocusHelper.attach(this, cornerRadiusPx = px(24).toFloat(), isButton = true)
            text = "Open Onyx TV"
            setOnClickListener { launchOnyxGeneral() }
        }
        errorStateContainer.addView(errorActionButton)

        addView(errorStateContainer)
    }

    // =========================================================================
    // LIFECYCLE & DATA LOADING
    // =========================================================================
    fun onTabSelected() {
        visibility = View.VISIBLE
        repository.registerObserver {
            refreshGuideData()
        }
        startNowLineTicker()
        refreshGuideData()
    }

    fun onTabUnselected() {
        repository.unregisterObserver()
        repository.dropCache()
        stopNowLineTicker()
        visibility = View.GONE
    }

    private fun startNowLineTicker() {
        stopNowLineTicker()
        val ticker = object : Runnable {
            override fun run() {
                updateNowLinePosition()
                mainHandler.postDelayed(this, 60000L) // 1 minute interval
            }
        }
        nowLineTicker = ticker
        mainHandler.post(ticker)
    }

    private fun stopNowLineTicker() {
        nowLineTicker?.let { mainHandler.removeCallbacks(it) }
        nowLineTicker = null
    }

    private fun refreshGuideData() {
        val onyxState = repository.checkOnyxState()
        when (onyxState) {
            OnyxState.NOT_INSTALLED -> {
                showErrorState("Not installed · Get Onyx TV", "Onyx TV is required for Live TV.", "Get Onyx TV") {
                    Toast.makeText(context, "Onyx TV is not installed", Toast.LENGTH_SHORT).show()
                }
                return
            }
            OnyxState.TURNED_OFF -> {
                showErrorState("Turned off", "Onyx TV has been disabled in system settings.", "App Info") {
                    val activePkg = LiveTvRepository.resolveActivePackage(context)
                    val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = android.net.Uri.fromParts("package", activePkg, null)
                    }
                    context.startActivity(intent)
                }
                return
            }
            OnyxState.READY, OnyxState.NO_PLAYLIST -> {
                hideErrorState()
            }
        }

        // 1. Fetch countries
        repository.getCountriesAsync { countries ->
            countriesList = countries

            if (countriesList.isNotEmpty()) {
                val matched = countriesList.firstOrNull { it.code == currentCountryCode }
                if (matched != null) {
                    currentCountryCode = matched.code
                    currentCountryName = matched.name
                } else {
                    currentCountryCode = countriesList.first().code
                    currentCountryName = countriesList.first().name
                    prefs.edit().putString("selected_country", currentCountryCode).apply()
                }
            } else {
                currentCountryCode = "all"
                currentCountryName = "All Channels"
            }
            gridCountryHeader.text = currentCountryName
            populateCountryChips()

            // 2. Determine initial focus channel from last watched
            repository.getLastWatchedChannelAsync { lastWatched ->
                if (!lastWatched.isNullOrEmpty()) {
                    lastWatchedChannelId = lastWatched
                }

                // 3. Align timeline slots
                val now = System.currentTimeMillis()
                timelineStartMs = (now / halfHourMs) * halfHourMs - halfHourMs // start 30m in past
                populateTimelineHeader()

                // 4. Fetch channels for current country
                loadChannelsAndProgrammes()
            }
        }
    }

    private fun loadChannelsAndProgrammes() {
        val isLow = dev.plume.DeviceTier.isLowTier(context)
        val channelLimit = if (isLow) 25 else 50
        repository.getChannelsAsync(
            countryCode = currentCountryCode,
            query = if (searchQuery.isNotBlank()) searchQuery else null,
            offset = 0,
            limit = channelLimit
        ) { channels ->
            channelsList = channels

            if (channels.isEmpty()) {
                if (searchQuery.isNotBlank()) {
                    showErrorState("No Channels Found", "No channels found matching '$searchQuery'.", "Clear Search") {
                        searchEditText.setText("")
                        searchEditText.requestFocus()
                    }
                } else if (countriesList.isNotEmpty() && currentCountryCode != "all" && currentCountryCode.isNotEmpty()) {
                    // Current category has no channels; fallback to "all"
                    currentCountryCode = "all"
                    currentCountryName = "All Channels"
                    prefs.edit().putString("selected_country", "all").apply()
                    gridCountryHeader.text = currentCountryName
                    populateCountryChips()
                    loadChannelsAndProgrammes()
                } else {
                    showErrorState(
                        "No Channels in Onyx TV",
                        "Please open Onyx TV to add an IPTV playlist or login to your account.",
                        "Open Onyx TV"
                    ) {
                        launchOnyxGeneral()
                    }
                }
                return@getChannelsAsync
            }

            hideErrorState()

            // Fetch programmes for these channels (from 1 hour ago to 4 hours ahead)
            val channelIds = channels.map { it.channelId }
            val fromMs = timelineStartMs
            val toMs = timelineStartMs + (halfHourMs * 8)
            repository.getProgrammesAsync(channelIds, fromMs, toMs) { progsMap ->
                programmesMap = progsMap
                populateChannelRows()

                // Request initial focus on last watched channel or first channel
                post {
                    focusInitialProgramme()
                }
            }
        }
    }

    // =========================================================================
    // RENDER: Country Chips
    // =========================================================================
    private fun populateCountryChips() {
        countryChipsContainer.removeAllViews()

        val displayedCountries = if (countriesList.size <= 50) {
            countriesList
        } else {
            val top = countriesList.take(50).toMutableList()
            val current = countriesList.firstOrNull { it.code == currentCountryCode }
            if (current != null && !top.contains(current)) {
                top.add(1, current)
            }
            top
        }

        for (country in displayedCountries) {
            val isSelected = country.code == currentCountryCode
            if (isSelected) {
                currentCountryName = country.name
                gridCountryHeader.text = currentCountryName
            }

            val chipIndex = displayedCountries.indexOf(country)

            val chip = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, px(48)).apply {
                    rightMargin = px(10)
                }
                gravity = Gravity.CENTER_VERTICAL
                setPadding(px(8), px(4), px(18), px(4))
                isFocusable = true
                isFocusableInTouchMode = true
                if (isSelected) {
                    tag = "selected_country_chip"
                }

                val chipBg = GradientDrawable().apply {
                    setColor(if (isSelected) Color.WHITE else Color.parseColor("#14FFFFFF"))
                    cornerRadius = px(24).toFloat()
                }
                background = chipBg

                TvFocusHelper.attach(this, cornerRadiusPx = px(24).toFloat(), isButton = true)

                setOnClickListener {
                    if (currentCountryCode != country.code) {
                        currentCountryCode = country.code
                        currentCountryName = country.name
                        prefs.edit().putString("selected_country", currentCountryCode).apply()
                        gridCountryHeader.text = currentCountryName
                        populateCountryChips()
                        loadChannelsAndProgrammes()
                    }
                }

                setOnKeyListener { _, keyCode, event ->
                    if (event.action == KeyEvent.ACTION_DOWN) {
                        when (keyCode) {
                            KeyEvent.KEYCODE_DPAD_UP -> {
                                onBackToTopBar?.invoke()
                                true
                            }
                            KeyEvent.KEYCODE_DPAD_LEFT -> {
                                if (chipIndex == 0) {
                                    searchEditText.requestFocus()
                                    true
                                } else false
                            }
                            KeyEvent.KEYCODE_DPAD_DOWN -> {
                                val airing = findViewWithTag<View>("initial_focus")
                                if (airing != null) {
                                    airing.requestFocus()
                                    true
                                } else if (programmesMatrixContainer.childCount > 0) {
                                    val firstRow = programmesMatrixContainer.getChildAt(0) as? LinearLayout
                                    firstRow?.getChildAt(0)?.requestFocus() ?: false
                                } else false
                            }
                            else -> false
                        }
                    } else false
                }
            }

            // Code Badge inside chip (34×32px, rounded 8px, 13px font)
            val badge = TextView(context).apply {
                layoutParams = LinearLayout.LayoutParams(px(34), px(32)).apply {
                    rightMargin = px(10)
                }
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_PX, px(13).toFloat())
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(if (isSelected) Color.WHITE else Color.parseColor("#C7CCD6"))
                text = when {
                    country.code.equals("all", ignoreCase = true) -> "ALL"
                    country.code.length <= 3 -> country.code.uppercase(Locale.getDefault())
                    else -> country.name.take(3).uppercase(Locale.getDefault())
                }
                background = GradientDrawable().apply {
                    setColor(if (isSelected) Color.parseColor("#090B10") else Color.parseColor("#1FFFFFFF"))
                    cornerRadius = px(8).toFloat()
                }
            }
            chip.addView(badge)

            // Country Name (19px font, weight 700)
            val nameView = TextView(context).apply {
                layoutParams = LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
                setTextSize(TypedValue.COMPLEX_UNIT_PX, px(19).toFloat())
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(if (isSelected) Color.parseColor("#090B10") else Color.parseColor("#C7CCD6"))
                text = country.name
            }
            chip.addView(nameView)

            countryChipsContainer.addView(chip)
        }
    }

    // =========================================================================
    // RENDER: Timeline Header
    // =========================================================================
    private fun populateTimelineHeader() {
        gridTimelineHeaderContainer.removeAllViews()
        val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

        // 8 slots (4 hours total)
        for (i in 0 until 8) {
            val slotTime = timelineStartMs + (i * halfHourMs)
            val slotText = timeFormat.format(Date(slotTime))

            val slotView = TextView(context).apply {
                layoutParams = LinearLayout.LayoutParams(slotWidthPx, LayoutParams.MATCH_PARENT)
                gravity = Gravity.CENTER_VERTICAL or Gravity.START
                setTextSize(TypedValue.COMPLEX_UNIT_PX, px(20).toFloat())
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(Color.parseColor("#929AAA"))
                text = slotText
            }
            gridTimelineHeaderContainer.addView(slotView)
        }
        updateNowLinePosition()
    }

    private fun updateNowLinePosition() {
        val now = System.currentTimeMillis()
        val elapsed = now - timelineStartMs
        val x = (elapsed.toDouble() / halfHourMs * slotWidthPx).toFloat() + px(340)
        nowLineOverlay.setNowX(x)
    }

    private fun getChannelBadgeText(channel: ChannelItem): String {
        return when (channel.number) {
            "101" -> "N1"
            "104" -> "O1"
            "112" -> "AR"
            "120" -> "CN"
            "131" -> "KD"
            else -> {
                val parts = channel.name.split(" ").filter { it.isNotEmpty() }
                if (parts.size >= 2) "${parts[0][0]}${parts[1][0]}".uppercase() else channel.name.take(2).uppercase()
            }
        }
    }

    private fun getChannelBadgeColor(channel: ChannelItem): Int {
        return when (channel.number) {
            "101" -> Color.parseColor("#2E5AA8")
            "104" -> Color.parseColor("#107575")
            "112" -> Color.parseColor("#AF5828")
            "120" -> Color.parseColor("#5B43A1")
            "131" -> Color.parseColor("#3E8E41")
            else -> Color.parseColor("#252A38")
        }
    }

    // =========================================================================
    // RENDER: Channel Rows & Programme Blocks
    // =========================================================================
    private fun populateChannelRows() {
        channelsColumnContainer.removeAllViews()
        programmesMatrixContainer.removeAllViews()
        renderedChannelCount = 0
        lastAirNow = System.currentTimeMillis()
        val isLow = dev.plume.DeviceTier.isLowTier(context)
        val initialBatch = if (isLow) Math.min(12, channelsList.size) else channelsList.size

        for (i in 0 until initialBatch) {
            appendChannelRow(channelsList[i], i, lastAirNow)
        }
        renderedChannelCount = initialBatch
    }

    private fun checkAppendMoreChannels(currentIndex: Int) {
        val isLow = dev.plume.DeviceTier.isLowTier(context)
        if (!isLow) return
        if (currentIndex >= renderedChannelCount - 3 && renderedChannelCount < channelsList.size) {
            val nextBatchEnd = Math.min(renderedChannelCount + 8, channelsList.size)
            for (i in renderedChannelCount until nextBatchEnd) {
                appendChannelRow(channelsList[i], i, lastAirNow)
            }
            renderedChannelCount = nextBatchEnd
        }
    }

    private fun appendChannelRow(channel: ChannelItem, rowIndex: Int, now: Long) {
        // Channel Label Cell (324px wide, 76px tall, radius 12px)
        val cellBg = GradientDrawable().apply {
            setColor(Color.parseColor("#141821"))
            cornerRadius = px(12).toFloat()
        }

        val channelCell = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(px(324), px(76)).apply {
                bottomMargin = px(8)
                rightMargin = px(16)
            }
            gravity = Gravity.CENTER_VERTICAL
            setPadding(px(16), 0, px(12), 0)
            isFocusable = true
            isFocusableInTouchMode = true
            background = cellBg

            TvFocusHelper.attach(this, cornerRadiusPx = px(12).toFloat(), isButton = true)

            setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    cellBg.setColor(Color.parseColor("#1C2130"))
                    updatePreviewDebounced(channel, null)
                    checkAppendMoreChannels(rowIndex)
                } else {
                    cellBg.setColor(Color.parseColor("#141821"))
                }
            }

            setOnKeyListener { _, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN) {
                    when (keyCode) {
                        KeyEvent.KEYCODE_DPAD_RIGHT -> {
                            val progsRow = programmesMatrixContainer.getChildAt(rowIndex) as? LinearLayout
                            if (progsRow != null && progsRow.childCount > 0) {
                                val nowTime = System.currentTimeMillis()
                                val progs = programmesMap[channel.channelId] ?: emptyList()
                                var targetView: View? = null
                                for (idx in progs.indices) {
                                    if (progs[idx].isAiringNow(nowTime)) {
                                        targetView = progsRow.getChildAt(idx)
                                        break
                                    }
                                }
                                (targetView ?: progsRow.getChildAt(0))?.requestFocus()
                                true
                            } else false
                        }
                        KeyEvent.KEYCODE_DPAD_UP -> {
                            if (rowIndex == 0) {
                                searchEditText.requestFocus()
                                true
                            } else false
                        }
                        else -> false
                    }
                } else false
            }

            setOnClickListener {
                launchOnyxChannel(channel.channelId, channel.name, 0L, false)
            }
        }

        // Channel Number (44px wide, 20px font, weight 700, #929AAA)
        val numberView = TextView(context).apply {
            layoutParams = LinearLayout.LayoutParams(px(44), LayoutParams.WRAP_CONTENT)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, px(20).toFloat())
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#929AAA"))
            text = channel.number
        }
        channelCell.addView(numberView)

        // Logo / Letter Badge (48×48px, radius 10px)
        val badgeColor = getChannelBadgeColor(channel)
        val letterCode = getChannelBadgeText(channel)

        val logoBadge = TextView(context).apply {
            layoutParams = LinearLayout.LayoutParams(px(48), px(48)).apply {
                rightMargin = px(14)
            }
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_PX, px(18).toFloat())
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            text = letterCode
            background = GradientDrawable().apply {
                setColor(badgeColor)
                cornerRadius = px(10).toFloat()
            }
        }
        channelCell.addView(logoBadge)

        // Channel Name (22px font, weight 700, white)
        val nameView = TextView(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, px(22).toFloat())
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            text = channel.name
        }
        channelCell.addView(nameView)

        channelsColumnContainer.addView(channelCell)

        // Programme Blocks Row
        val progsRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, px(76)).apply {
                bottomMargin = px(8)
            }
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            clipToPadding = false
        }

        val progs = programmesMap[channel.channelId] ?: emptyList()
        val effectiveProgs = if (progs.isEmpty()) {
            val windowEnd = timelineStartMs + (halfHourMs * 8)
            listOf(
                ProgrammeItem(
                    channelId = channel.channelId,
                    start = timelineStartMs,
                    end = windowEnd,
                    title = channel.name.ifBlank { "Live Broadcast" },
                    genre = null,
                    imageUri = channel.logoUri,
                    hasCatchup = false
                )
            )
        } else {
            progs
        }
        val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

        for (prog in effectiveProgs) {
            val progIndex = effectiveProgs.indexOf(prog)
            val durationMs = Math.max(prog.end - prog.start, halfHourMs)
            val blockWidth = (durationMs.toDouble() / halfHourMs * slotWidthPx).toInt() - px(6)
            val isAiring = prog.isAiringNow(now)

            val blockView = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(blockWidth, px(76)).apply {
                    rightMargin = px(6)
                }
                gravity = Gravity.CENTER_VERTICAL
                setPadding(px(20), 0, px(20), 0)
                isFocusable = true
                isFocusableInTouchMode = true

                val blockBg = GradientDrawable().apply {
                    setColor(if (isAiring) Color.parseColor("#1C2130") else Color.parseColor("#141821"))
                    cornerRadius = px(12).toFloat()
                }
                background = blockBg

                // Program Title (21px font, weight 700)
                val pTitle = TextView(context).apply {
                    layoutParams = LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                        bottomMargin = px(4)
                    }
                    setTextSize(TypedValue.COMPLEX_UNIT_PX, px(21).toFloat())
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(Color.WHITE)
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    text = prog.title
                }
                addView(pTitle)

                // Program Time Range (17px font, weight 600, 75% opacity)
                val pTime = TextView(context).apply {
                    layoutParams = LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
                    setTextSize(TypedValue.COMPLEX_UNIT_PX, px(17).toFloat())
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    setTextColor(Color.parseColor("#B0FFFFFF"))
                    text = "${timeFormat.format(Date(prog.start))} - ${timeFormat.format(Date(prog.end))}"
                }
                addView(pTime)

                setOnFocusChangeListener { _, hasFocus ->
                    if (hasFocus) {
                        blockBg.setColor(Color.WHITE)
                        blockBg.setStroke(px(4), Color.parseColor("#8B7CFF"))
                        pTitle.setTextColor(Color.parseColor("#090B10"))
                        pTime.setTextColor(Color.parseColor("#80090B10"))
                        cellBg.setColor(Color.parseColor("#1C2130"))
                        updatePreviewDebounced(channel, prog)
                        checkAppendMoreChannels(rowIndex)
                    } else {
                        blockBg.setColor(if (isAiring) Color.parseColor("#1C2130") else Color.parseColor("#141821"))
                        blockBg.setStroke(0, Color.TRANSPARENT)
                        pTitle.setTextColor(Color.WHITE)
                        pTime.setTextColor(Color.parseColor("#B0FFFFFF"))
                        cellBg.setColor(Color.parseColor("#141821"))
                    }
                }

                setOnKeyListener { _, keyCode, event ->
                    if (event.action == KeyEvent.ACTION_DOWN) {
                        when (keyCode) {
                            KeyEvent.KEYCODE_DPAD_LEFT -> {
                                if (progIndex == 0) {
                                    channelCell.requestFocus()
                                    true
                                } else false
                            }
                            KeyEvent.KEYCODE_DPAD_UP -> {
                                if (rowIndex == 0) {
                                    val chip = findViewWithTag<View>("selected_country_chip") ?: countryChipsContainer.getChildAt(0)
                                    chip?.requestFocus()
                                    true
                                } else false
                            }
                            else -> false
                        }
                    } else false
                }

                setOnClickListener {
                    launchOnyxChannel(channel.channelId, channel.name, prog.start, prog.hasCatchup && prog.isPast(now))
                }

                tag = if (channel.channelId == lastWatchedChannelId && isAiring) "initial_focus" else null
            }

            progsRow.addView(blockView)
        }

        programmesMatrixContainer.addView(progsRow)
    }

    private fun focusInitialProgramme() {
        val initialView = findViewWithTag<View>("initial_focus")
        if (initialView != null) {
            initialView.requestFocus()
        } else if (programmesMatrixContainer.childCount > 0) {
            val firstRow = programmesMatrixContainer.getChildAt(0) as? LinearLayout
            if (firstRow != null && firstRow.childCount > 0) {
                firstRow.getChildAt(0).requestFocus()
            } else if (channelsColumnContainer.childCount > 0) {
                channelsColumnContainer.getChildAt(0).requestFocus()
            }
        } else if (channelsColumnContainer.childCount > 0) {
            channelsColumnContainer.getChildAt(0).requestFocus()
        }
    }

    // =========================================================================
    // PREVIEW UPDATE (Debounced 250ms)
    // =========================================================================
    private fun updatePreviewDebounced(channel: ChannelItem, programme: ProgrammeItem?) {
        focusedChannel = channel
        focusedProgramme = programme

        pendingPreviewUpdate?.let { mainHandler.removeCallbacks(it) }
        val runnable = Runnable {
            applyPreviewUpdate(channel, programme)
        }
        pendingPreviewUpdate = runnable
        mainHandler.postDelayed(runnable, 250L)
    }

    private fun applyPreviewUpdate(channel: ChannelItem, programme: ProgrammeItem?) {
        val now = System.currentTimeMillis()
        val prog = programme ?: (programmesMap[channel.channelId]?.firstOrNull { it.isAiringNow(now) }
            ?: programmesMap[channel.channelId]?.firstOrNull())

        // Channel Tag: 22px #42D6C5
        previewTagView.text = "CH ${channel.number} · ${channel.name.uppercase(Locale.getDefault())}"

        // Programme Title: 56px white
        previewTitleView.text = prog?.title ?: channel.name

        // Time format: 22px
        val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
        if (prog != null) {
            val genreStr = if (!prog.genre.isNullOrBlank()) " · ${prog.genre}" else ""
            previewMetaView.text = "${timeFormat.format(Date(prog.start))} – ${timeFormat.format(Date(prog.end))}$genreStr"

            // Progress bar
            if (prog.isAiringNow(now)) {
                previewBadge.visibility = View.VISIBLE
                previewProgressBarContainer.visibility = View.VISIBLE
                val elapsed = now - prog.start
                val total = Math.max(prog.end - prog.start, 1L)
                val pct = (elapsed.toDouble() / total).coerceIn(0.0, 1.0)
                val fillWidth = (px(520) * pct).toInt()
                previewProgressBarFill.layoutParams = FrameLayout.LayoutParams(fillWidth, LayoutParams.MATCH_PARENT)
            } else {
                previewBadge.visibility = View.GONE
                previewProgressBarContainer.visibility = View.GONE
            }

            // Next Programme: 22px #929AAA
            val channelProgs = programmesMap[channel.channelId] ?: emptyList()
            val nextProg = channelProgs.firstOrNull { it.start >= prog.end }
            if (nextProg != null) {
                previewNextView.visibility = View.VISIBLE
                previewNextView.text = "Next · ${timeFormat.format(Date(nextProg.start))} · ${nextProg.title}"
            } else {
                previewNextView.visibility = View.GONE
            }
        } else {
            previewMetaView.text = "Live Broadcast"
            previewBadge.visibility = View.VISIBLE
            previewProgressBarContainer.visibility = View.GONE
            previewNextView.visibility = View.GONE
        }

        // Preview Logo text
        val letterCode = getChannelBadgeText(channel)
        previewLogoText.text = letterCode
    }

    // =========================================================================
    // INTENT DISPATCH & NAVIGATION
    // =========================================================================
    private fun launchOnyxChannel(channelId: String, channelName: String?, programmeStartMs: Long, isCatchup: Boolean) {
        try {
            val pkg = LiveTvRepository.resolveActivePackage(context)
            val intent = Intent().apply {
                setClassName(pkg, "$pkg.MainActivity")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra("onyx.extra.DESTINATION", "play_live")
                putExtra("DESTINATION", "play_live")
                putExtra("onyx.extra.CHANNEL_ID", channelId)
                putExtra("CHANNEL_ID", channelId)
                if (!channelName.isNullOrBlank()) {
                    putExtra("onyx.extra.CHANNEL_NAME", channelName)
                    putExtra("CHANNEL_NAME", channelName)
                }
                putExtra("onyx.extra.CALLER", "plume")
                putExtra("CALLER", "plume")
                if (isCatchup && programmeStartMs > 0L) {
                    putExtra("onyx.extra.PROGRAMME_START", programmeStartMs)
                    putExtra("PROGRAMME_START", programmeStartMs)
                }
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(context, "Could not launch channel: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun launchOnyxGeneral() {
        try {
            val pkg = LiveTvRepository.resolveActivePackage(context)
            val intent = Intent().apply {
                setClassName(pkg, "$pkg.MainActivity")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                putExtra("onyx.extra.DESTINATION", "live")
                putExtra("DESTINATION", "live")
                putExtra("onyx.extra.CALLER", "plume")
                putExtra("CALLER", "plume")
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(context, "Could not open TV app", Toast.LENGTH_SHORT).show()
        }
    }

    private fun launchVoiceSearch() {
        try {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PROMPT, "Say a channel or programme name")
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            searchEditText.requestFocus()
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showSoftInput(searchEditText, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    fun handleBackPressed(): Boolean {
        if (searchQuery.isNotEmpty()) {
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.hideSoftInputFromWindow(searchEditText.windowToken, 0)
            searchEditText.setText("")
            searchEditText.requestFocus()
            return true
        }
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(searchEditText.windowToken, 0)

        val currentFocus = findFocus()
        if (currentFocus != null && isViewInGrid(currentFocus)) {
            countryChipsScroll.findFocus()
                ?: countryChipsContainer.getChildAt(0)?.requestFocus()
                ?: searchEditText.requestFocus()
            return true
        }
        if (currentFocus != null && (currentFocus == searchEditText || currentFocus == searchBoxView || isViewInCountryChips(currentFocus))) {
            onBackToTopBar?.invoke()
            return true
        }
        return false
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (handleBackPressed()) return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun isViewInGrid(view: View): Boolean {
        var p = view.parent
        while (p != null) {
            if (p == channelsColumnContainer || p == programmesMatrixContainer || p == gridVerticalScroll) return true
            p = p.parent
        }
        return false
    }

    private fun isViewInCountryChips(view: View): Boolean {
        var p = view.parent
        while (p != null) {
            if (p == countryChipsContainer || p == countryChipsScroll) return true
            p = p.parent
        }
        return false
    }

    private fun showErrorState(title: String, subtitle: String, buttonText: String, onAction: () -> Unit) {
        contentContainer.visibility = View.GONE
        errorStateContainer.visibility = View.VISIBLE
        errorTitleView.text = title
        errorSubtitleView.text = subtitle
        errorActionButton.text = buttonText
        errorActionButton.setOnClickListener { onAction() }
        errorActionButton.requestFocus()
    }

    private fun hideErrorState() {
        errorStateContainer.visibility = View.GONE
        contentContainer.visibility = View.VISIBLE
    }

    // =========================================================================
    // NOW LINE OVERLAY VIEW
    // =========================================================================
    private class NowLineOverlayView(context: Context) : View(context) {
        private var baseNowX: Float = -100f
        private var scrollXOffset: Int = 0
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FF5A6E")
            strokeWidth = 3f * (resources.displayMetrics.widthPixels / 1920f)
            style = Paint.Style.STROKE
        }

        fun setNowX(x: Float) {
            baseNowX = x
            invalidate()
        }

        fun setScrollXOffset(offset: Int) {
            scrollXOffset = offset
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val currentX = baseNowX - scrollXOffset
            val minX = 340f * (resources.displayMetrics.widthPixels / 1920f)
            if (currentX >= minX) {
                canvas.drawLine(currentX, 0f, currentX, height.toFloat(), paint)
            }
        }
    }
}
