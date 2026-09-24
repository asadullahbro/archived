package com.callforwarder.app

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Pushes a "wake up" alert to the iPhone via ntfy, since iOS suspends Linphone in the background. */
object NtfyNotifier {
    fun push(ctx: Context, title: String, message: String) {
        var base = Prefs.ntfyUrl(ctx).trim().trimEnd('/')
        val topic = Prefs.ntfyTopic(ctx).trim()
        val token = Prefs.ntfyToken(ctx).trim()
        if (base.isBlank() || topic.isBlank()) return
        if (!base.contains("://")) base = "https://$base"

        Thread {
            try {
                val body = JSONObject()
                    .put("topic", topic)
                    .put("title", title)
                    .put("message", message)
                    .put("priority", 5)
                    .put("click", "linphone-sip://")
                    .put("tags", JSONArray().put("phone"))
                val conn = URL(base).openConnection() as HttpURLConnection
                try {
                    conn.requestMethod = "POST"
                    conn.doOutput = true
                    conn.connectTimeout = 10_000
                    conn.readTimeout = 10_000
                    conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    if (token.isNotBlank()) conn.setRequestProperty("Authorization", "Bearer $token")
                    conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                    Log.i(BridgeService.TAG, "ntfy push -> HTTP ${conn.responseCode}")
                } finally {
                    conn.disconnect()
                }
            } catch (e: Exception) {
                Log.w(BridgeService.TAG, "ntfy push failed", e)
            }
        }.start()
    }
}
