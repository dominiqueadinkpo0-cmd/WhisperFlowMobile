package com.flowmic

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import kotlinx.coroutines.*

/**
 * Coeur de FlowMic : affiche le micro flottant et injecte le texte dicté
 * dans le champ focus de n'importe quelle app (comme Wispr Flow).
 */
class FlowService : AccessibilityService() {

    companion object {
        var instance: FlowService? = null
        const val TAG = "FlowService"
    }

    private var overlay: MicOverlay? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        overlay = MicOverlay(this)
        scope.launch {
            try {
                if (first(prefsFlow()).overlayEnabled) tryShowOverlay()
            } catch (e: Exception) {
                Log.w(TAG, "prefs read failed", e)
            }
        }
    }

    private suspend fun <T> first(flow: kotlinx.coroutines.flow.Flow<T>): T =
        kotlinx.coroutines.flow.first(flow)

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        overlay?.destroy()
        overlay = null
        instance = null
        scope.cancel()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        overlay?.destroy()
        scope.cancel()
        instance = null
        super.onDestroy()
    }

    fun tryShowOverlay() {
        if (android.provider.Settings.canDrawOverlays(this)) overlay?.show()
    }

    fun hideOverlay() = overlay?.hide()

    fun onDictated(rawText: String) {
        scope.launch {
            val prefs = try { first(prefsFlow()) } catch (_: Exception) { AppPrefs() }
            var text = rawText.trim()
            if (text.isEmpty()) return@launch
            if (prefs.autoSpace) text = " $text"
            val ok = insertText(text.trimStart())
            applicationContext.pushHistory(rawText.trim())
            Toast.makeText(
                applicationContext,
                if (ok) "✓ \"$rawText\" tapé" else "Copié : colle avec Ctrl+V / appui long",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    /** Injection : 1) SET_TEXT sur champ focus 2) presse-papiers + PASTE */
    fun insertText(text: String): Boolean {
        val focused = findFocusedEditText(rootInActiveWindow)
        if (focused != null) {
            val existing = focused.text?.toString() ?: ""
            val merged = if (existing.isEmpty()) text else "$existing $text"
            val args = Bundle().apply {
                putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, merged
                )
            }
            if (focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
                Log.i(TAG, "insert via SET_TEXT")
                return true
            }
        }
        return insertViaClipboard(text)
    }

    private fun insertViaClipboard(text: String): Boolean {
        return try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("flowmic", text))
            val focused = findFocusedEditText(rootInActiveWindow)
            if (focused != null && focused.performAction(AccessibilityNodeInfo.ACTION_PASTE)) {
                Log.i(TAG, "insert via PASTE")
                true
            } else {
                Log.w(TAG, "texte copié, collage manuel requis")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "clipboard error", e)
            false
        }
    }

    private fun findFocusedEditText(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isFocused && (node.isEditable || node.className?.contains("EditText") == true)) return node
        for (i in 0 until node.childCount) {
            val found = findFocusedEditText(node.getChild(i))
            if (found != null) return found
        }
        return null
    }
}
