package com.example.virtualmouse

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.Toast
import org.json.JSONObject

/** Ширина окна трекбола при масштабе 100 % (dp) — то же число, что в TrackballWindow. */
private const val JOY_BALL_BASE_DP = 260

/**
 * Голографический дрон Joy (assets/joy.html) на весь экран.
 * Вызывается кнопкой с тремя полосками на трекболе. На голо-экране лежат настройки дрона и настройки
 * трекбола/курсора; изменения трекбола уходят в onTbState (в TrackballWindow).
 * Пока голо-экран закрыт, окно не ловит касания (FLAG_NOT_TOUCHABLE) и не мешает приложениям под ним.
 */
@SuppressLint("SetJavaScriptEnabled")
class JoyWindow(
    private val ctx: Context,
    private val wm: WindowManager,
    private val getSettings: () -> CursorSettings,
    private val onTbState: (String) -> Unit,
    private val onHidden: () -> Unit
) {
    private val handler = Handler(Looper.getMainLooper())
    private var root: FrameLayout? = null
    private var web: WebView? = null

    val isShown: Boolean get() = root != null

    private val lp = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
    }

    private inner class Bridge {
        @JavascriptInterface fun getTbState(): String = getSettings().tbState

        @JavascriptInterface fun getTbInfo(): String {
            val s = getSettings()
            val o = JSONObject()
            o.put("sens", s.sensitivity.toDouble())
            o.put("ws", s.ballWidthDp.toDouble() / JOY_BALL_BASE_DP)
            return o.toString()
        }

        @JavascriptInterface fun setTbState(json: String) { handler.post { onTbState(json) } }
        @JavascriptInterface fun setTouchable(on: Int) { handler.post { applyFlag(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE, on != 1) } }
        @JavascriptInterface fun setFocusable(on: Int) { handler.post { setFocus(on == 1) } }
        @JavascriptInterface fun onJoyHidden() { handler.post { onHidden() } }
    }

    private fun applyFlag(flag: Int, set: Boolean) {
        val v = root ?: return
        lp.flags = if (set) lp.flags or flag else lp.flags and flag.inv()
        try {
            wm.updateViewLayout(v, lp)
        } catch (_: Exception) {
        }
    }

    /** Окну ввода API-ключа нужен фокус, чтобы появилась клавиатура. */
    private fun setFocus(on: Boolean) {
        applyFlag(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, !on)
        val w = web ?: return
        val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        if (on) {
            w.requestFocus()
            handler.postDelayed({ try { imm?.showSoftInput(w, InputMethodManager.SHOW_IMPLICIT) } catch (_: Exception) { } }, 300)
        } else {
            try {
                imm?.hideSoftInputFromWindow(w.windowToken, 0)
            } catch (_: Exception) {
            }
        }
    }

    fun show() {
        if (root != null) return
        val themed = ContextThemeWrapper(ctx, R.style.Theme_VirtualMouse)
        val frame = FrameLayout(themed)
        frame.setBackgroundColor(Color.TRANSPARENT)
        val w = WebView(themed)
        w.setBackgroundColor(Color.TRANSPARENT)
        w.isVerticalScrollBarEnabled = false
        w.isHorizontalScrollBarEnabled = false
        w.overScrollMode = View.OVER_SCROLL_NEVER
        w.settings.javaScriptEnabled = true
        w.settings.domStorageEnabled = true      // настройки дрона и ключ Gemini лежат в localStorage
        w.settings.setSupportZoom(false)
        w.settings.mediaPlaybackRequiresUserGesture = false
        w.setOnLongClickListener { true }
        w.isLongClickable = false
        w.addJavascriptInterface(Bridge(), "Android")
        frame.addView(
            w,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        )
        try {
            wm.addView(frame, lp)
            root = frame
            web = w
            w.loadUrl("file:///android_asset/joy.html")
        } catch (_: Exception) {
            Toast.makeText(ctx, "Нет разрешения «Поверх других окон»", Toast.LENGTH_SHORT).show()
            try {
                w.destroy()
            } catch (_: Exception) {
            }
        }
    }

    /** Кнопка с тремя полосками нажата, когда дрон уже показан: открыть/закрыть голо-экран. */
    fun toggleScreen() {
        web?.evaluateJavascript("window.joyToggle&&joyToggle()", null)
    }

    /** Перечитать настройки трекбола (если они изменились вне голо-окна). */
    fun refresh() {
        web?.evaluateJavascript("window.joyRefresh&&joyRefresh()", null)
    }

    fun hide() {
        root?.let {
            try {
                wm.removeView(it)
            } catch (_: Exception) {
            }
        }
        root = null
        web?.let {
            try {
                it.removeJavascriptInterface("Android")
                it.destroy()
            } catch (_: Exception) {
            }
        }
        web = null
    }
}
