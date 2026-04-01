package com.rr.aido.harper

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.RectF
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

class HarperOverlayManager(
    private val context: Context,
    private val windowManager: WindowManager
) {
    private val TAG = "HarperOverlayManager"

    private var composeView: ComposeView? = null
    private var underlineView: ComposeView? = null

    private var lintsToShow: List<HarperManager.HarperLint> = emptyList()
    private var drawnRects: List<RectF> = emptyList()
    private var currentNode: AccessibilityNodeInfo? = null

    fun drawLints(node: AccessibilityNodeInfo, lints: List<HarperManager.HarperLint>) {
        hideAll()
        if (lints.isEmpty()) return

        this.lintsToShow = lints
        this.currentNode = node

        val rects = mutableListOf<RectF>()
        for (lint in lints) {
            val r = calculateWordPosition(node, lint.span.start, lint.span.end)
            if (r != null) rects.add(r)
        }

        if (rects.isEmpty()) return
        this.drawnRects = rects

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        )

        underlineView = buildComposeView { lifecycleOwner ->
            lifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
            HarperUnderlinePainter(
                rects = drawnRects,
                onClick = { index ->
                    val clickedLint = lintsToShow.getOrNull(index) ?: return@HarperUnderlinePainter
                    showTypoCard(clickedLint, drawnRects[index])
                }
            )
        }

        try {
            windowManager.addView(underlineView, params)
        } catch (e: Exception) {
            Log.e(TAG, "Error adding Underline view", e)
        }
    }

    private fun calculateWordPosition(node: AccessibilityNodeInfo, startIdx: Int, endIdx: Int): RectF? {
        val getBounds = fun(idx: Int): RectF? {
            val args = Bundle()
            args.putInt(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_START_INDEX, idx)
            args.putInt(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_LENGTH, 1)

            val refreshed = node.refreshWithExtraData(
                AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY,
                args
            )
            if (refreshed) {
                val extras = node.extras ?: return null
                if (Build.VERSION.SDK_INT >= 33) {
                    val arr = extras.getParcelableArray(
                        AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY,
                        RectF::class.java
                    )
                    return arr?.firstOrNull()
                } else {
                    @Suppress("DEPRECATION")
                    val arr = extras.getParcelableArray(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY)
                    return arr?.firstOrNull() as? RectF
                }
            }
            return null
        }

        val startR = getBounds(startIdx)
        val endR = getBounds(if (endIdx > startIdx) endIdx - 1 else startIdx)

        return if (startR != null && endR != null) {
            // Vertical bounds come from startR so the underline stays on the
            // same text line even if endR is on a different line.
            RectF(startR.left, startR.top, endR.right, startR.bottom)
        } else null

    }

    private fun showTypoCard(lint: HarperManager.HarperLint, rect: RectF) {
        removeCard()

        val margin = 20
        var popupY = rect.bottom.toInt() + margin
        if (popupY > context.resources.displayMetrics.heightPixels - 500) {
            popupY = rect.top.toInt() - 400
        }

        val params = WindowManager.LayoutParams(
            800,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            x = (rect.left.toInt() - 40).coerceAtLeast(0)
            y = popupY
        }

        composeView = buildComposeView { _ ->
            HarperTypoCard(
                lint = lint,
                onDismiss = { hideAll() },
                onApply = { correction ->
                    applyCorrection(lint, correction)
                    hideAll()
                }
            )
        }

        try {
            windowManager.addView(composeView, params)
        } catch (e: Exception) {
            Log.e(TAG, "Error adding Typo Card", e)
        }
    }

    private fun applyCorrection(lint: HarperManager.HarperLint, correction: String) {
        val node = currentNode ?: return
        val currentText = node.text?.toString() ?: return

        try {
            val before = currentText.substring(0, lint.span.start)
            val after = currentText.substring(lint.span.end)
            val newText = before + correction + after

            val args = Bundle()
            args.putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                newText
            )
            node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)

            val bundle = Bundle()
            bundle.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, before.length + correction.length)
            bundle.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, before.length + correction.length)
            node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, bundle)
        } catch (e: Exception) {
            Log.e(TAG, "Failed applying typo fix", e)
        }
    }

    fun removeCard() {
        composeView?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
        }
        composeView = null
    }

    fun hideAll() {
        removeCard()
        underlineView?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
        }
        underlineView = null
        lintsToShow = emptyList()
        drawnRects = emptyList()
        currentNode = null
    }

    // Helper to build a ComposeView with the correct lifecycle owners
    private fun buildComposeView(
        content: @androidx.compose.runtime.Composable (HarperLifecycleOwner) -> Unit
    ): ComposeView {
        val lifecycleOwner = HarperLifecycleOwner()
        val savedStateOwner = HarperSavedStateOwner(lifecycleOwner)
        savedStateOwner.performRestore(null)
        lifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_START)

        return ComposeView(context).apply {
            setViewTreeLifecycleOwner(lifecycleOwner)
            setViewTreeSavedStateRegistryOwner(savedStateOwner)
            setContent { content(lifecycleOwner) }
        }
    }
}

// Minimal LifecycleOwner for overlays
class HarperLifecycleOwner : LifecycleOwner {
    private val registry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = registry
    fun handleLifecycleEvent(event: Lifecycle.Event) = registry.handleLifecycleEvent(event)
}

// Minimal SavedStateRegistryOwner for overlays
class HarperSavedStateOwner(private val lo: LifecycleOwner) : SavedStateRegistryOwner {
    private val controller = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = lo.lifecycle
    override val savedStateRegistry: SavedStateRegistry get() = controller.savedStateRegistry
    fun performRestore(state: Bundle?) = controller.performRestore(state)
}
