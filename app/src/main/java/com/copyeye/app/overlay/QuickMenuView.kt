package com.copyeye.app.overlay

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/** The actions reachable from a long press on Iris. */
enum class QuickAction(val label: String) {
    ScanScreen("Scan screen"),
    ScanArea("Scan selected area"),
    History("Clipboard history"),
    Pause("Pause floating eye"),
    HideForAnHour("Hide for 1 hour"),
    Settings("Settings"),
    StopService("Stop CopyEye"),
}

/**
 * A compact vertical menu shown beside the eye.
 *
 * Built in code rather than inflated: it lives in a window owned by a service, where the theming
 * that an XML layout would rely on does not apply, and the whole thing is seven rows.
 */
class QuickMenuView(
    context: Context,
    private val onAction: (QuickAction) -> Unit,
) : LinearLayout(context) {

    private val density = resources.displayMetrics.density
    private val isDark: Boolean = run {
        val mode = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        mode == Configuration.UI_MODE_NIGHT_YES
    }

    private val surfaceColor = if (isDark) 0xF21B1D26.toInt() else 0xF2FFFFFF.toInt()
    private val textColor = if (isDark) 0xFFE9EAF0.toInt() else 0xFF15171E.toInt()
    private val dangerColor = 0xFFE05B5B.toInt()

    init {
        orientation = VERTICAL
        val pad = dp(8)
        setPadding(pad, pad, pad, pad)
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(18).toFloat()
            setColor(surfaceColor)
            setStroke(dp(1), if (isDark) 0x1FFFFFFF else 0x14000000)
        }
        elevation = dp(12).toFloat()
        QuickAction.entries.forEach { action -> addView(rowFor(action)) }
    }

    private fun rowFor(action: QuickAction): View = TextView(context).apply {
        text = action.label
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        setTextColor(if (action == QuickAction.StopService) dangerColor else textColor)
        gravity = Gravity.CENTER_VERTICAL
        // 48dp keeps every row at the minimum accessible touch target.
        minHeight = dp(48)
        setPadding(dp(14), dp(6), dp(20), dp(6))
        isClickable = true
        isFocusable = true
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(12).toFloat()
            setColor(Color.TRANSPARENT)
        }
        contentDescription = action.label
        setOnClickListener { onAction(action) }
    }

    private fun dp(value: Int): Int = (value * density).toInt()
}
