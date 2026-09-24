package com.callforwarder.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var domain: EditText
    private lateinit var port: EditText
    private lateinit var username: EditText
    private lateinit var password: EditText
    private lateinit var target: EditText
    private lateinit var ntfyUrl: EditText
    private lateinit var ntfyTopic: EditText
    private lateinit var ntfyToken: EditText
    private lateinit var enabled: Switch
    private lateinit var status: TextView

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            Toast.makeText(this, "Permissions updated", Toast.LENGTH_SHORT).show()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        load()
    }

    override fun onResume() {
        super.onResume()
        Status.listener = { status.text = "Status: $it" }
        status.text = "Status: ${Status.text}"
        enabled.isChecked = Prefs.enabled(this)
    }

    override fun onPause() {
        Status.listener = null
        super.onPause()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun label(t: String) = TextView(this).apply {
        text = t
        setPadding(0, dp(12), 0, dp(2))
    }

    private fun field(input: Int = InputType.TYPE_CLASS_TEXT) = EditText(this).apply { inputType = input }

    private fun button(t: String, onClick: () -> Unit) = Button(this).apply {
        text = t
        setOnClickListener { onClick() }
    }

    private fun buildUi(): ScrollView {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }

        status = TextView(this).apply { textSize = 15f }
        root.addView(status)

        root.addView(button("Grant permissions") { requestPermissions() })
        root.addView(button("Disable battery optimization") { requestBatteryExemption() })

        root.addView(label("SIP server"))
        domain = field(); root.addView(domain)
        root.addView(label("TLS port"))
        port = field(InputType.TYPE_CLASS_NUMBER); root.addView(port)
        root.addView(label("SIP username (this phone)"))
        username = field(); root.addView(username)
        root.addView(label("SIP password"))
        password = field(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD); root.addView(password)
        root.addView(label("Extension to ring (the iPhone)"))
        target = field(); root.addView(target)

        root.addView(label("ntfy server (wakes the iPhone)"))
        ntfyUrl = field(); root.addView(ntfyUrl)
        root.addView(label("ntfy topic"))
        ntfyTopic = field(); root.addView(ntfyTopic)
        root.addView(label("ntfy access token"))
        ntfyToken = field(); root.addView(ntfyToken)

        enabled = Switch(this).apply {
            text = "Forward incoming calls to the iPhone"
            setPadding(0, dp(16), 0, dp(8))
        }
        root.addView(enabled)

        root.addView(button("Save") { save() })
        root.addView(button("Test SIP call to iPhone") {
            save()
            BridgeService.send(this, BridgeService.ACTION_TEST, null)
        })
        root.addView(button("Hang up test call") {
            BridgeService.send(this, BridgeService.ACTION_HANGUP, null)
        })

        return ScrollView(this).apply { addView(root) }
    }

    private fun load() {
        domain.setText(Prefs.domain(this))
        port.setText(Prefs.port(this).toString())
        username.setText(Prefs.username(this))
        password.setText(Prefs.password(this))
        target.setText(Prefs.target(this))
        ntfyUrl.setText(Prefs.ntfyUrl(this))
        ntfyTopic.setText(Prefs.ntfyTopic(this))
        ntfyToken.setText(Prefs.ntfyToken(this))
        enabled.isChecked = Prefs.enabled(this)
    }

    private fun save() {
        Prefs.save(
            this, domain.text.toString(), port.text.toString().toIntOrNull() ?: 8000,
            username.text.toString(), password.text.toString(), target.text.toString(), enabled.isChecked,
            ntfyUrl.text.toString(), ntfyTopic.text.toString(), ntfyToken.text.toString(),
        )
        Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
    }

    private fun requestPermissions() {
        val perms = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.ANSWER_PHONE_CALLS,
        )
        if (Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.POST_NOTIFICATIONS)
        if (Build.VERSION.SDK_INT >= 31) perms.add(Manifest.permission.BLUETOOTH_CONNECT)
        permissionLauncher.launch(perms.toTypedArray())
    }

    private fun requestBatteryExemption() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            Toast.makeText(this, "Already unrestricted", Toast.LENGTH_SHORT).show()
        } else {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        }
    }
}
