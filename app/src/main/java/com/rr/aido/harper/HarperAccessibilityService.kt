package com.rr.aido.harper

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.rr.aido.data.DataStoreManager
import com.rr.aido.data.models.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class HarperAccessibilityService(
    private val context: Context,
    private val viewScope: CoroutineScope,
    private val windowManager: android.view.WindowManager
) {
    private val TAG = "HarperAccessibilityServ"
    
    private val harperManager = HarperManager(context)
    private val overlayManager = HarperOverlayManager(context, windowManager)
    
    private var dataStoreManager: DataStoreManager = DataStoreManager(context)
    
    private var checkJob: Job? = null
    private var lastCheckedText: String = ""
    
    // We only observe if it's turned on in settings
    private var isHarperEnabled: Boolean = false

    init {
        harperManager.initialize()
        
        viewScope.launch {
            dataStoreManager.settingsFlow.collect { settings ->
                isHarperEnabled = settings.isHarperEnabled
                if (!isHarperEnabled) {
                    overlayManager.hideAll()
                }
            }
        }
    }

    fun onAccessibilityEvent(event: AccessibilityEvent, node: AccessibilityNodeInfo?) {
        if (!isHarperEnabled) return
        
        if (event.eventType != AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_VIEW_CLICKED &&
            event.eventType != AccessibilityEvent.TYPE_VIEW_FOCUSED
        ) {
            return
        }

        if (node == null || !node.isEditable) {
            overlayManager.hideAll()
            return
        }
        
        val sequence = node.text
        if (sequence.isNullOrEmpty()) {
            overlayManager.hideAll()
            lastCheckedText = ""
            return
        }

        val currentText = sequence.toString()
        if (currentText == lastCheckedText) {
            // Either the text didn't change (just clicked/focused), we redraw if needed
            // Actually, we shouldn't redraw blindly, but cursor might have moved.
            return
        }

        // Debounce typing to avoid running WASM/Draw 60 times a sec
        checkJob?.cancel()
        checkJob = viewScope.launch {
            delay(1000) // 1 second debounce
            
            lastCheckedText = currentText
            
            // Check grammar offline via WebView bridge
            val lints = harperManager.checkText(currentText)
            
            if (lints.isEmpty()) {
                overlayManager.hideAll()
            } else {
                // If there are errors, draw the underlines on screen coordinates
                overlayManager.drawLints(node, lints)
            }
        }
    }
    
    fun onDestroy() {
        checkJob?.cancel()
        overlayManager.hideAll()
        harperManager.destroy()
    }
}
