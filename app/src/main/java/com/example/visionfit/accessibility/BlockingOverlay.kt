package com.example.visionfit.accessibility

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
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
            )
        }

        titleView = TextView(context).apply {
            textSize = 22f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER_HORIZONTAL
        }
        detailView = TextView(context).apply {
            textSize = 16f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER_HORIZONTAL
        }
        val button = Button(context).apply {
            text = "Go home"
            setOnClickListener { onExitApp() }
        }

        content.addView(titleView)
        content.addView(detailView)
        content.addView(button)
        root.addView(content)
        return root
    }

    private fun dp(value: Int): Int {
        val density = context.resources.displayMetrics.density
        return (value * density).toInt()
    }
}
