package com.example.virtualmouse

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

class OverlayService : Service() {

    companion object {
        @Volatile
        var isRunning = false
        const val ACTION_STOP = "com.example.virtualmouse.ACTION_STOP"
        private const val CHANNEL_ID = "virtual_mouse_overlay"
        private const val NOTIF_ID = 1
    }

    private lateinit var wm: WindowManager
    private lateinit var settings: CursorSettings
    private var cursor: CursorOverlay? = null
    private var button: FloatingButton? = null
    private var pad: PadWindow? = null
    private var padStyleBuilt = 0
    private var settingsWindow: SettingsWindow? = null
    private var stylus: StylusWindow? = null
    private var joy: JoyWindow? = null
    private var started = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        settings = PreferenceHelper.load(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        enterForeground()
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!started) {
            started = true
            setup()
        }
        return START_STICKY
    }

    private fun enterForeground() {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, OverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW)
            )
        }
        val n: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text))
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(0, getString(R.string.notif_stop), stop)
            .build()
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        else ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST
        ServiceCompat.startForeground(this, NOTIF_ID, n, type)
    }

    private fun setup() {
        val c = CursorOverlay(this, wm)
        cursor = c
        c.applySettings(settings)
        updateScreen()

        pad = buildPad(c)

        button = FloatingButton(
            this, wm,
            getSettings = { settings },
            save = { persist() },
            onTap = {
                if (stylus?.isShown == true) closeStylus(true)
                else if (pad?.isShown == true) hidePad() else showPad()
            },
            onLongTap = { toggleSettings() }
        ).also { it.show() }

        settingsWindow = SettingsWindow(
            this, wm, settings,
            onChanged = { applyAll() },
            onSave = { persist() },
            onStylus = { showStylus() }
        )
        showPad()
    }

    /** Создаёт окно управления курсором выбранного стиля: 0 = тачпад, 1 = трекбол Orbit X. */
    private fun buildPad(c: CursorOverlay): PadWindow {
        padStyleBuilt = settings.padStyle
        val save = { persist() }
        return if (settings.padStyle == 1) {
            TrackballWindow(
                this, wm, c,
                getSettings = { settings },
                save = save,
                onSettings = { toggleSettings() },
                onKeyboard = { showPicker() },
                onHide = { hidePad() },
                onJoy = { toggleJoy() },
                onSwitchToTouchpad = {
                    // Двойной тап по ЛКМ трекбола -> меню -> «Тачпад»
                    settings.padStyle = 0
                    persist()
                    applyAll()
                }
            )
        } else {
            TouchpadWindow(
                this, wm, c,
                getSettings = { settings },
                save = save,
                onSettings = { toggleSettings() },
                onKeyboard = { showPicker() },
                onHide = { hidePad() }
            )
        }
    }

    /** Язык меняет клавиатура (IME) в тех же настройках: не затираем его устаревшим значением. */
    private fun persist() {
        settings.language = PreferenceHelper.load(this).language
        PreferenceHelper.save(this, settings)
    }

    private fun showPad() {
        pad?.show()
        cursor?.show()
        cursor?.bringToFront()
    }

    private fun hidePad() {
        hideJoy()
        pad?.hide()
        cursor?.hide()
    }

    // ---------- Умный стилус NOVA ----------

    /** Открывает стилус: тачпад/трекбол и курсор скрываются, поверх экрана появляется NOVA. */
    private fun showStylus() {
        hideJoy()
        settingsWindow?.hide()
        hidePad()
        if (stylus == null) {
            stylus = StylusWindow(
                this, wm,
                getPadName = { if (settings.padStyle == 1) "Трекбол" else "Тачпад" },
                onReturnPad = { closeStylus(true) },
                onClose = { closeStylus(false) }
            )
        }
        stylus?.show()
    }

    /** returnPad = true: закрыть стилус и вернуть тачпад/трекбол; false: только закрыть (вернуть можно плавающей кнопкой). */
    private fun closeStylus(returnPad: Boolean) {
        stylus?.hide()
        stylus = null
        if (returnPad) showPad()
    }

    // ---------- Дрон Joy (кнопка с тремя полосками на трекболе) ----------

    private fun toggleJoy() {
        val j = joy
        if (j != null && j.isShown) {
            j.toggleScreen()
            return
        }
        val w = JoyWindow(
            this, wm,
            getSettings = { settings },
            onTbState = { json -> (pad as? TrackballWindow)?.applyExternalState(json) },
            onHidden = { hideJoy() }
        )
        joy = w
        w.show()
        // Трекбол и курсор должны оставаться над голо-окном, иначе им нельзя управлять
        (pad as? TrackballWindow)?.bringToFront()
        cursor?.bringToFront()
    }

    private fun hideJoy() {
        joy?.hide()
        joy = null
    }

    private fun toggleSettings() {
        settingsWindow?.show()
    }

    private fun applyAll() {
        cursor?.applySettings(settings)
        val c = cursor
        if (c != null && settings.padStyle != padStyleBuilt) {
            hideJoy()
            // Смена стиля указателя: пересоздаём окно, сохраняя «показано/скрыто»
            val wasShown = pad?.isShown == true
            pad?.destroy()
            pad = buildPad(c)
            if (wasShown) showPad()
        }
        pad?.applySettings()
    }

    private fun showPicker() {
        try {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showInputMethodPicker()
        } catch (_: Exception) {
        }
    }

    private fun updateScreen() {
        val w: Int
        val h: Int
        if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.currentWindowMetrics.bounds
            w = b.width()
            h = b.height()
        } else {
            val dm = android.util.DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(dm)
            w = dm.widthPixels
            h = dm.heightPixels
        }
        cursor?.setScreen(w, h)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateScreen()
        pad?.applySettings()
    }

    override fun onDestroy() {
        isRunning = false
        settingsWindow?.hide()
        stylus?.hide()
        joy?.hide()
        pad?.destroy()
        button?.destroy()
        cursor?.destroy()
        settingsWindow = null
        stylus = null
        joy = null
        pad = null
        button = null
        cursor = null
        super.onDestroy()
    }
}
