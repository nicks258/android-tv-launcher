package dev.plume

import android.animation.TimeInterpolator
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.TextView

/**
 * Universal Android TV Focus helper implementing the exact Phase 2 shared focus rules:
 * - 4px ring matching component shape (circle, pill, or card corner radius) with 5px offset
 * - Dynamic border color support matching app brand / icon background color
 * - Scale 1.06
 * - Soft drop shadow (elevation/translationZ)
 * - 150ms ease-out (DecelerateInterpolator)
 * - Focused buttons: white fill (#FFFFFF) with dark text/icon (#090B10) preserving exact shape
 * - Zero blurs
 */
object TvFocusHelper {

    val EASE_OUT: TimeInterpolator = DecelerateInterpolator(1.5f)
    const val FOCUS_DURATION_MS = 150L
    const val COLOR_ACCENT = 0xFF8B7CFF.toInt()
    const val COLOR_INVERTED_BG = 0xFFFFFFFF.toInt()
    const val COLOR_INVERTED_FG = 0xFF090B10.toInt()

    /**
     * Attaches focus animation, scale 1.06, elevation, shape-preserving focus ring with 5px offset,
     * and optional button inversion.
     */
    fun attach(
        view: View,
        cornerRadiusPx: Float = 20f,
        isButton: Boolean = false,
        isCircle: Boolean = false,
        isPill: Boolean = false,
        focusColor: Int? = null,
        onFocusChanged: ((Boolean) -> Unit)? = null
    ) {
        val density = view.resources.displayMetrics.density
        val strokeWidth = (2.0f * density).toInt().coerceAtLeast(4)
        val offsetPx = (2.5f * density).toInt().coerceAtLeast(4)
        val activeColor = focusColor ?: COLOR_ACCENT

        // Store original view states to restore on blur
        var origTextColor: ColorStateList? = null
        val origBg: Drawable? = view.background
        val origForeground: Drawable? = view.foreground
        val origColorFilter = (view as? ImageView)?.colorFilter
        val origImageTintList = (view as? ImageView)?.imageTintList

        if (view is TextView) {
            origTextColor = view.textColors
        }

        view.setOnFocusChangeListener { _, hasFocus ->
            // Scale to 1.06 on focus, 1.0 on blur with 150ms ease-out
            val targetScale = if (hasFocus) 1.06f else 1.0f
            val targetElevation = if (hasFocus) 8f * density else 0f

            view.animate()
                .scaleX(targetScale)
                .scaleY(targetScale)
                .translationZ(targetElevation)
                .setDuration(FOCUS_DURATION_MS)
                .setInterpolator(EASE_OUT)
                .start()

            val isPillEffective = isPill || cornerRadiusPx >= 100f

            if (isButton) {
                if (hasFocus) {
                    // Focused button:
                    // Layer 0: Outer focus ring matching button shape (OVAL for circle, RECTANGLE for pill/rounded button)
                    // Layer 1: Inset white fill with #090B10 text/icon
                    val outerRing = GradientDrawable().apply {
                        if (isCircle) {
                            shape = GradientDrawable.OVAL
                        } else {
                            shape = GradientDrawable.RECTANGLE
                            cornerRadius = if (isPillEffective) 999f * density else (cornerRadiusPx + offsetPx)
                        }
                        setColor(Color.TRANSPARENT)
                        setStroke(strokeWidth, activeColor)
                    }

                    val innerFill = GradientDrawable().apply {
                        if (isCircle) {
                            shape = GradientDrawable.OVAL
                        } else {
                            shape = GradientDrawable.RECTANGLE
                            cornerRadius = if (isPillEffective) 999f * density else cornerRadiusPx
                        }
                        setColor(COLOR_INVERTED_BG)
                    }

                    val layers = LayerDrawable(arrayOf(outerRing, innerFill)).apply {
                        setLayerInset(1, offsetPx, offsetPx, offsetPx, offsetPx)
                    }
                    view.background = layers

                    if (view is TextView) {
                        view.setTextColor(COLOR_INVERTED_FG)
                    } else if (view is ImageView) {
                        view.setColorFilter(COLOR_INVERTED_FG, PorterDuff.Mode.SRC_IN)
                    }
                } else {
                    view.background = origBg
                    if (view is TextView && origTextColor != null) {
                        view.setTextColor(origTextColor)
                    } else if (view is ImageView) {
                        if (origImageTintList != null) {
                            view.imageTintList = origImageTintList
                        } else if (origColorFilter != null) {
                            view.colorFilter = origColorFilter
                        } else {
                            view.clearColorFilter()
                        }
                    }
                }
            } else {
                // Non-button items (Cards, Tabs, Chips): retain content/background, draw stroke via foreground
                if (hasFocus) {
                    val focusRing = GradientDrawable().apply {
                        if (isCircle) {
                            shape = GradientDrawable.OVAL
                        } else {
                            shape = GradientDrawable.RECTANGLE
                            cornerRadius = if (isPillEffective) 999f * density else cornerRadiusPx
                        }
                        setColor(Color.TRANSPARENT)
                        setStroke(strokeWidth, activeColor)
                    }
                    view.foreground = focusRing
                } else {
                    view.foreground = origForeground
                }
            }

            onFocusChanged?.invoke(hasFocus)
        }
    }

    /**
     * Attaches focus animation and outer focus ring with 5px offset to an app card tile.
     * The cardView holds the banner/icon and is clipped to cornerRadiusPx.
     * The tile container draws the outer focus ring along its bounds on focus, providing
     * a clean, unclipped gap between the card and the border matching the app's brand color.
     */
    fun attachCard(
        tile: ViewGroup,
        cardView: View,
        cornerRadiusPx: Float = 20f,
        focusColor: Int? = null,
        onFocusChanged: ((Boolean) -> Unit)? = null
    ) {
        val density = tile.resources.displayMetrics.density
        val strokeWidth = (2.0f * density).toInt().coerceAtLeast(4)
        val offsetPx = (2.5f * density).toInt().coerceAtLeast(4)
        val activeColor = focusColor ?: COLOR_ACCENT

        // Ensure inner card strictly clips its content (banner image, logos) to rounded corners
        cardView.outlineProvider = ViewOutlineProvider.BACKGROUND
        cardView.clipToOutline = true

        tile.isFocusable = true
        tile.clipChildren = false
        tile.clipToPadding = false

        tile.setOnFocusChangeListener { _, hasFocus ->
            val targetScale = if (hasFocus) 1.06f else 1.0f
            val targetElevation = if (hasFocus) 8f * density else 0f

            tile.animate()
                .scaleX(targetScale)
                .scaleY(targetScale)
                .translationZ(targetElevation)
                .setDuration(FOCUS_DURATION_MS)
                .setInterpolator(EASE_OUT)
                .start()

            if (hasFocus) {
                val outerRing = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = cornerRadiusPx + offsetPx
                    setColor(Color.TRANSPARENT)
                    setStroke(strokeWidth, activeColor)
                }
                tile.background = outerRing
            } else {
                tile.background = null
            }

            onFocusChanged?.invoke(hasFocus)
        }
    }
}
