package com.callforwarder.app

import android.os.Handler
import android.os.Looper

object Status {
    @Volatile var text: String = "Idle"
        private set
    @Volatile var listener: ((String) -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())

    fun set(value: String) {
        text = value
        main.post { listener?.invoke(value) }
    }
}
