package com.callforwarder.app

import android.content.Context

object Prefs {
    private const val FILE = "call_forwarder_prefs"

    private fun p(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun domain(ctx: Context): String = p(ctx).getString("domain", "sip.asdl.website") ?: ""
    fun port(ctx: Context): Int = p(ctx).getInt("port", 8000)
    fun username(ctx: Context): String = p(ctx).getString("username", "android") ?: ""
    fun password(ctx: Context): String = p(ctx).getString("password", "") ?: ""
    fun target(ctx: Context): String = p(ctx).getString("target", "iphone") ?: ""
    fun enabled(ctx: Context): Boolean = p(ctx).getBoolean("enabled", false)
    fun setEnabled(ctx: Context, value: Boolean) { p(ctx).edit().putBoolean("enabled", value).apply() }
    fun ntfyUrl(ctx: Context): String = p(ctx).getString("ntfyUrl", "https://ntfy.asdl.website") ?: ""
    fun ntfyTopic(ctx: Context): String = p(ctx).getString("ntfyTopic", "sms-forward") ?: ""
    fun ntfyToken(ctx: Context): String = p(ctx).getString("ntfyToken", "") ?: ""

    fun save(
        ctx: Context, domain: String, port: Int, username: String,
        password: String, target: String, enabled: Boolean,
        ntfyUrl: String, ntfyTopic: String, ntfyToken: String,
    ) {
        p(ctx).edit()
            .putString("domain", domain.trim())
            .putInt("port", port)
            .putString("username", username.trim())
            .putString("password", password)
            .putString("target", target.trim())
            .putBoolean("enabled", enabled)
            .putString("ntfyUrl", ntfyUrl.trim())
            .putString("ntfyTopic", ntfyTopic.trim())
            .putString("ntfyToken", ntfyToken.trim())
            .apply()
    }

    fun isConfigured(ctx: Context): Boolean =
        domain(ctx).isNotBlank() && username(ctx).isNotBlank() &&
            password(ctx).isNotBlank() && target(ctx).isNotBlank()
}
