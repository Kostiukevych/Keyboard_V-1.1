package com.example.virtualmouse

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle

/**
 * Прозрачная активность: открывает системный выбор фото для окна стилуса NOVA
 * (оверлей-служба сама не может показать выбор файла) и возвращает результат в WebView.
 */
class ImagePickActivity : Activity() {

    companion object {
        /** Кому вернуть выбранное фото (null в массиве = отмена). Ставит StylusWindow. */
        @Volatile
        var callback: ((Array<Uri>?) -> Unit)? = null
        private const val REQ = 4711
    }

    private var delivered = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return
        try {
            val i = Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "image/*"
                addCategory(Intent.CATEGORY_OPENABLE)
            }
            @Suppress("DEPRECATION")
            startActivityForResult(Intent.createChooser(i, "Выберите фото"), REQ)
        } catch (_: Exception) {
            deliver(null)
            finish()
        }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        deliver(if (resultCode == RESULT_OK && uri != null) arrayOf(uri) else null)
        finish()
    }

    private fun deliver(r: Array<Uri>?) {
        if (delivered) return
        delivered = true
        val cb = callback
        callback = null
        cb?.invoke(r)
    }

    override fun onDestroy() {
        deliver(null)   // если закрыли без результата, WebView не должен ждать вечно
        super.onDestroy()
    }
}
