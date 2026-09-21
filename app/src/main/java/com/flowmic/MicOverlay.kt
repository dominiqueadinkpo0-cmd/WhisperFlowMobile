package com.flowmic

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.*

/**
 * Bouton micro flottant : déplaçable, visible par-dessus toutes les apps.
 * - Appui LONG (maintenir) : dicte jusqu'au relâchement
 * - Tap court : dicte 8 secondes
 */
class MicOverlay(private val ctx: Context) {

    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val handler = Handler(Looper.getMainLooper())
    private var showing = false

    private var btn: FrameLayout? = null
    private var icon: TextView? = null
    private var transcriber: Transcriber? = null

    fun show() {
        if (showing) return
        showing = true
        transcriber = Transcriber(ctx) { text ->
            FlowService.instance?.onDictated(text)
            resetIcon()
        }
        scope.launch {
            val prefs = kotlinx.coroutines.flow.first(ctx.prefsFlow())
            createButton(prefs)
        }
    }

    fun hide() {
        showing = false
        transcriber?.destroy()
        transcriber = null
        try { btn?.let { wm.removeView(it) } } catch (_: Exception) {}
        btn = null
        scope.cancel()
    }

    fun destroy() = hide()

    private fun createButton(prefs: AppPrefs) {
        val density = ctx.resources.displayMetrics.density
        val size = (prefs.micSize * density).toInt()
        val dm = ctx.resources.displayMetrics

        val frame = FrameLayout(ctx).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#FF1E88E5"))
                setStroke((2 * density).toInt(), Color.WHITE)
            }
            elevation = 14f
        }
        val ic = TextView(ctx).apply {
            text = "🎤"
            gravity = Gravity.CENTER
            textSize = 26f
        }
        frame.addView(ic, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        icon = ic

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else WindowManager.LayoutParams.TYPE_PHONE
        val params = WindowManager.LayoutParams(
            size, size, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = if (prefs.micX >= 0) prefs.micX
                else dm.widthPixels - size - (14 * density).toInt()
            y = if (prefs.micY >= 0) prefs.micY else dm.heightPixels / 2
        }

        var longPress = false
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0
        val longTask = Runnable {
            longPress = true
            setRecording(true)
            vibrate(30)
            scope.launch {
                val p = kotlinx.coroutines.flow.first(ctx.prefsFlow())
                transcriber?.start(p.mode, p.language, p.apiKey)
            }
        }

        frame.setOnTouchListener { _, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.rawX; downY = ev.rawY
                    startX = params.x; startY = params.y
                    longPress = false
                    handler.postDelayed(longTask, 200)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - downX
                    val dy = ev.rawY - downY
                    if (!longPress && (kotlin.math.abs(dx) > 14 || kotlin.math.abs(dy) > 14)) {
                        handler.removeCallbacks(longTask)
                        params.x = (startX + dx).toInt()
                        params.y = (startY + dy).toInt()
                        try { wm.updateViewLayout(frame, params) } catch (_: Exception) {}
                        return@setOnTouchListener true
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    handler.removeCallbacks(longTask)
                    if (longPress) {
                        transcriber?.stop()
                        setRecording(false)
                        vibrate(15)
                    } else {
                        // Tap court : si on enregistre déjà, stop ; sinon dicte 8 s
                        if (transcriber?.isListening() == true) {
                            transcriber?.stop()
                            setRecording(false)
                        } else {
                            setRecording(true)
                            vibrate(20)
                            scope.launch {
                                val p = kotlinx.coroutines.flow.first(ctx.prefsFlow())
                                transcriber?.start(p.mode, p.language, p.apiKey)
                            }
                            handler.postDelayed({
                                if (transcriber?.isListening() == true) {
                                    transcriber?.stop()
                                    setRecording(false)
                                }
                            }, 8000)
                        }
                    }
                    // Sauve position
                    scope.launch {
                        ctx.dataStore.edit {
                            it[PrefsKeys.MIC_X] = params.x
                            it[PrefsKeys.MIC_Y] = params.y
                        }
                    }
                    longPress = false
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(longTask)
                    if (longPress) transcriber?.stop()
                    setRecording(false)
                    longPress = false
                    true
                }
                else -> false
            }
        }
        try { wm.addView(frame, params); btn = frame } catch (e: Exception) { e.printStackTrace() }
    }

    private fun setRecording(rec: Boolean) {
        val density = ctx.resources.displayMetrics.density
        btn?.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor(if (rec) "#FFE53935" else "#FF1E88E5"))
            setStroke((2 * density).toInt(), Color.WHITE)
        }
        icon?.text = if (rec) "●" else "🎤"
    }

    private fun resetIcon() = setRecording(false)

    private fun vibrate(ms: Long) {
        try {
            val v = ctx.getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                v.vibrate(android.os.VibrationEffect.createOneShot(
                    ms, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
            else v.vibrate(ms)
        } catch (_: Exception) {}
    }
}
