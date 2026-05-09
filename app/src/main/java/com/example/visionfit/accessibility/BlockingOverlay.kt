package com.example.visionfit.accessibility

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.getSystemService
import com.example.visionfit.model.AppBlockMode

enum class BlockingOverlayWindowType {
    ACCESSIBILITY,
    APPLICATION
}

class BlockingOverlay(
    private val context: Context,
    private val onExitApp: () -> Unit,
    private val onEarnCredits: () -> Unit,
    private val windowType: BlockingOverlayWindowType = BlockingOverlayWindowType.ACCESSIBILITY
) {
    private val windowManager: WindowManager = requireNotNull(context.getSystemService())
    private val layoutParams: WindowManager.LayoutParams = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        overlayLayoutType(),
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
    }

    private fun overlayLayoutType(): Int {
        return when (windowType) {
            BlockingOverlayWindowType.ACCESSIBILITY -> WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            BlockingOverlayWindowType.APPLICATION -> when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ->
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else -> {
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_PHONE
                }
            }
        }
    }

    private var overlayView: View? = null
    private var titleView: TextView? = null
    private var detailView: TextView? = null
    private var showing = false

    val isShowing: Boolean
        get() = showing

    fun show(appLabel: String, mode: AppBlockMode) {
        if (overlayView == null) {
            overlayView = buildOverlayView()
        }
        updateMessage(appLabel, mode)
        layoutParams.flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        overlayView?.requestFocus()
        if (!showing) {
            windowManager.addView(overlayView, layoutParams)
            showing = true
        } else {
            overlayView?.let { windowManager.updateViewLayout(it, layoutParams) }
        }
    }

    fun hide() {
        if (showing && overlayView != null) {
            windowManager.removeView(overlayView)
            showing = false
        }
    }

    private fun updateMessage(appLabel: String, mode: AppBlockMode) {
        titleView?.text = "Blocked"
        val suffix = when {
            mode == AppBlockMode.REELS_ONLY && windowType == BlockingOverlayWindowType.APPLICATION ->
                "All screens (Reels-only needs Accessibility)"
            mode == AppBlockMode.REELS_ONLY -> "Reels only"
            else -> "All screens"
        }
        detailView?.text = "No credits left. $appLabel is blocked ($suffix)."
    }

    private fun buildOverlayView(): View {
        val root = FrameLayout(context).apply {
            // Keep the original transparent "block screen" vibe.
            setBackgroundColor(0xCC000000.toInt())
            isClickable = true
            isFocusableInTouchMode = true
            isFocusable = true
            setOnTouchListener { _, _ -> true }
        }

        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(24), dp(24), dp(24))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            ).apply {
                marginStart = dp(24)
                marginEnd = dp(24)
            }
        }

        titleView = TextView(context).apply {
            textSize = 22f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER_HORIZONTAL
            setTypeface(typeface, Typeface.BOLD)
        }
        detailView = TextView(context).apply {
            textSize = 15f
            setTextColor(0xCCFFFFFF.toInt())
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(8), 0, 0)
        }
        val spacer = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(16)
            )
        }

        val buttonRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val earnButton = Button(context).apply {
            text = "Earn credits"
            setOnClickListener { onEarnCredits() }
            isAllCaps = false
            // Lime + white has poor contrast; use app "ink" for readability.
            setTextColor(0xFF0C0F14.toInt())
            textSize = 16f
            background = GradientDrawable().apply {
                // Match app theme "lime" family (see ui/theme/Color.kt).
                setColor(0xFFC6FF3D.toInt())
                cornerRadius = dpF(14)
            }
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                weight = 1f
            }
        }
        val homeButton = Button(context).apply {
            text = "Go home"
            setOnClickListener { onExitApp() }
            isAllCaps = false
            setTextColor(Color.WHITE)
            textSize = 16f
            background = GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                cornerRadius = dpF(14)
                setStroke(dp(2), 0xCCFFFFFF.toInt())
            }
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                weight = 1f
                marginStart = dp(10)
            }
        }

        buttonRow.addView(earnButton)
        buttonRow.addView(homeButton)

        content.addView(titleView)
        content.addView(detailView)
        content.addView(spacer)
        content.addView(buttonRow)
        root.addView(content)
        return root
    }

    private fun dp(value: Int): Int {
        val density = context.resources.displayMetrics.density
        return (value * density).toInt()
    }

    private fun dpF(value: Int): Float {
        val density = context.resources.displayMetrics.density
        return value * density
    }
}
