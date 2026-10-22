package com.example.virtualmouse

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Base64
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.Toast
import java.io.File

/**
 * Окно «Умный стилус NOVA» (assets/stylus.html) поверх всего экрана.
 * Пока оно открыто, тачпад/трекбол и курсор скрыты (это делает OverlayService).
 *  - «Вернуть тачпад/трекбол» -> onReturnPad (стилус закрывается, указатель возвращается),
 *  - «Закрыть стилус»        -> onClose (стилус закрывается, указатель вернёт плавающая кнопка).
 */
@SuppressLint("SetJavaScriptEnabled")
class StylusWindow(
    private val ctx: Context,
    private val wm: WindowManager,
    private val getPadName: () -> String,
    private val onReturnPad: () -> Unit,
    private val onClose: () -> Unit
) {
    private val handler = Handler(Looper.getMainLooper())
    private var root: FrameLayout? = null
    private var web: WebView? = null

    val isShown: Boolean get() = root != null

    private val lp = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
    }

    private inner class Bridge {
        @JavascriptInterface fun getPadName(): String = this@StylusWindow.getPadName()
        @JavascriptInterface fun onReturnPad() { handler.post { this@StylusWindow.onReturnPad() } }
        @JavascriptInterface fun onCloseStylus() { handler.post { this@StylusWindow.onClose() } }
        @JavascriptInterface fun onFold(on: Int) { /* холст свёрнут/развёрнут: окно остаётся на весь экран */ }
        @JavascriptInterface fun savePng(base64: String): Boolean = saveToGallery(base64)
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
        w.settings.domStorageEnabled = true      // автосохранение и настройки NOVA лежат в localStorage
        w.settings.allowContentAccess = true
        w.settings.setSupportZoom(false)
        w.settings.mediaPlaybackRequiresUserGesture = false
        w.setOnLongClickListener { true }
        w.isLongClickable = false
        w.addJavascriptInterface(Bridge(), "Android")
        w.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                if (filePathCallback == null) return false
                ImagePickActivity.callback?.invoke(null)
                ImagePickActivity.callback = { r -> filePathCallback.onReceiveValue(r) }
                try {
                    ctx.startActivity(
                        Intent(ctx, ImagePickActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                } catch (_: Exception) {
                    ImagePickActivity.callback = null
                    filePathCallback.onReceiveValue(null)
                }
                return true
            }
        }
        frame.addView(
            w,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        )
        try {
            wm.addView(frame, lp)
            root = frame
            web = w
            w.loadUrl("file:///android_asset/stylus.html")
        } catch (_: Exception) {
            Toast.makeText(ctx, "Нет разрешения «Поверх других окон»", Toast.LENGTH_SHORT).show()
            try {
                w.destroy()
            } catch (_: Exception) {
            }
        }
    }

    fun hide() {
        ImagePickActivity.callback?.invoke(null)
        ImagePickActivity.callback = null
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

    /** Сохраняет PNG в галерею: Pictures/NOVA (Android 10+) или в папку приложения на старых версиях. */
    private fun saveToGallery(base64: String): Boolean {
        return try {
            val bytes = Base64.decode(base64, Base64.DEFAULT)
            val name = "nova-" + System.currentTimeMillis() + ".png"
            if (Build.VERSION.SDK_INT >= 29) {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, name)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/NOVA")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                val uri = ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                if (uri == null) {
                    false
                } else {
                    ctx.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                    values.clear()
                    values.put(MediaStore.Images.Media.IS_PENDING, 0)
                    ctx.contentResolver.update(uri, values, null, null)
                    handler.post { Toast.makeText(ctx, "Сохранено: Pictures/NOVA", Toast.LENGTH_SHORT).show() }
                    true
                }
            } else {
                val dir = File(ctx.getExternalFilesDir(Environment.DIRECTORY_PICTURES), "NOVA")
                dir.mkdirs()
                File(dir, name).writeBytes(bytes)
                handler.post { Toast.makeText(ctx, "Сохранено: " + dir.absolutePath, Toast.LENGTH_LONG).show() }
                true
            }
        } catch (_: Exception) {
            false
        }
    }
}
