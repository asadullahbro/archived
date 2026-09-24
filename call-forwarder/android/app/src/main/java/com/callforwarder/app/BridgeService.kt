package com.callforwarder.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.os.PowerManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.telecom.TelecomManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import org.linphone.core.Account
import org.linphone.core.AudioDevice
import org.linphone.core.Call
import org.linphone.core.Core
import org.linphone.core.CoreListenerStub
import org.linphone.core.Factory
import org.linphone.core.MediaEncryption
import org.linphone.core.RegistrationState
import org.linphone.core.TransportType

/**
 * Owns the SIP client. On an incoming cellular call it rings the iPhone over SIP first;
 * only when the iPhone answers does it answer the cellular call, force speakerphone and
 * bridge the audio (speaker out / microphone in). Hang-ups propagate in both directions.
 */
class BridgeService : Service() {

    companion object {
        const val TAG = "CallFwd"
        const val ACTION_RING = "ring"
        const val ACTION_OFFHOOK = "offhook"
        const val ACTION_IDLE = "idle"
        const val ACTION_TEST = "test"
        const val ACTION_HANGUP = "hangup"
        private const val EXTRA_NUMBER = "number"
        private const val CHANNEL_ID = "bridge"
        private const val RING_TIMEOUT_MS = 70_000L

        fun send(ctx: Context, action: String, number: String?) {
            val i = Intent(ctx, BridgeService::class.java).setAction(action).putExtra(EXTRA_NUMBER, number)
            ContextCompat.startForegroundService(ctx, i)
        }
    }

    private enum class Mode { NONE, CELLULAR, TEST }

    private val main = Handler(Looper.getMainLooper())
    private var core: Core? = null
    private var mode = Mode.NONE
    private var sipCall: Call? = null
    private var cellularAnswered = false
    private var pendingInvite = false
    private var callerNumber: String? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var cpuLock: PowerManager.WakeLock? = null

    private val listener = object : CoreListenerStub() {
        override fun onAccountRegistrationStateChanged(core: Core, account: Account, state: RegistrationState, message: String) {
            Log.i(TAG, "registration: $state ($message)")
            when (state) {
                RegistrationState.Ok -> {
                    Status.set("SIP registered")
                    if (pendingInvite) placeSipCall()
                }
                RegistrationState.Failed -> Status.set("SIP registration failed: $message")
                else -> {}
            }
        }

        override fun onCallStateChanged(core: Core, call: Call, state: Call.State, message: String) {
            Log.i(TAG, "call state: $state ($message)")
            if (call != sipCall) {
                if (state == Call.State.IncomingReceived) call.decline(org.linphone.core.Reason.Declined)
                return
            }
            when (state) {
                Call.State.OutgoingRinging -> Status.set("Ringing the iPhone...")
                Call.State.StreamsRunning -> onIphoneAnswered(call)
                Call.State.Error -> {
                    Status.set("SIP call failed: $message")
                    onSipCallEnded()
                }
                Call.State.End, Call.State.Released -> onSipCallEnded()
                else -> {}
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat()
        acquireLocks()
        val number = intent?.getStringExtra(EXTRA_NUMBER)
        when (intent?.action) {
            ACTION_RING -> handleRing(number)
            ACTION_OFFHOOK -> handleOffhook()
            ACTION_IDLE -> handleIdle()
            ACTION_TEST -> handleTest()
            ACTION_HANGUP -> handleIdle()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        releaseLocks()
        core?.removeListener(listener)
        core?.stop()
        core = null
        super.onDestroy()
    }

    // ---- call flow ----------------------------------------------------------------

    private fun handleRing(number: String?) {
        if (number != null) callerNumber = number
        if (mode != Mode.NONE) return
        Log.i(TAG, "cellular call ringing from $callerNumber")
        mode = Mode.CELLULAR
        cellularAnswered = false
        Status.set("Incoming call ${callerNumber ?: ""} - ringing the iPhone")
        NtfyNotifier.push(this, "Incoming call", "From ${callerNumber ?: "unknown"} - open Linphone to answer")
        startSip()
    }

    private fun handleTest() {
        if (mode != Mode.NONE) return
        mode = Mode.TEST
        Status.set("Test: calling the iPhone")
        NtfyNotifier.push(this, "Incoming call (test)", "Open Linphone to answer the test call")
        startSip()
    }

    private fun handleOffhook() {
        // Answered manually on the Android (not by us): stop ringing the iPhone.
        if (mode == Mode.CELLULAR && !cellularAnswered) {
            Log.i(TAG, "answered on the Android itself, cancelling SIP call")
            sipCall?.terminate()
            finishSoon()
        }
    }

    private fun handleIdle() {
        Log.i(TAG, "idle / hangup")
        sipCall?.terminate()
        core?.terminateAllCalls()
        finishSoon()
    }

    private fun onIphoneAnswered(call: Call) {
        Status.set("iPhone answered - bridging audio")
        main.removeCallbacks(ringTimeout)
        routeToSpeaker(call)
        if (mode == Mode.CELLULAR && !cellularAnswered) {
            cellularAnswered = true
            answerCellular()
            main.postDelayed({ routeToSpeaker(call) }, 1500)
        }
    }

    private fun onSipCallEnded() {
        sipCall = null
        main.removeCallbacks(ringTimeout)
        if (mode == Mode.CELLULAR && cellularAnswered) {
            // iPhone side hung up: end the cellular call too.
            endCellular()
        } else if (mode == Mode.CELLULAR) {
            Status.set("iPhone did not answer - call keeps ringing on the Android")
        }
        finishSoon()
    }

    private val ringTimeout = Runnable {
        if (sipCall != null && !cellularAnswered && mode != Mode.NONE) {
            Log.i(TAG, "ring timeout")
            sipCall?.terminate()
        }
    }

    private fun finishSoon() {
        main.postDelayed({
            mode = Mode.NONE
            cellularAnswered = false
            sipCall = null
            callerNumber = null
            pendingInvite = false
            Status.set("Idle")
            stopForeground(true)
            stopSelf()
        }, 1500)
    }

    // ---- cellular control ---------------------------------------------------------

    private fun telecom() = getSystemService(Context.TELECOM_SERVICE) as TelecomManager

    private fun answerCellular() {
        try {
            @Suppress("MissingPermission")
            telecom().acceptRingingCall()
            Log.i(TAG, "cellular call answered")
        } catch (e: SecurityException) {
            Log.e(TAG, "cannot answer: missing ANSWER_PHONE_CALLS", e)
            Status.set("Missing permission to answer calls")
        }
    }

    private fun endCellular() {
        try {
            @Suppress("MissingPermission")
            val ended = telecom().endCall()
            Log.i(TAG, "cellular endCall -> $ended")
        } catch (e: SecurityException) {
            Log.e(TAG, "cannot end call", e)
        }
    }

    private fun routeToSpeaker(call: Call) {
        val c = core ?: return
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.isSpeakerphoneOn = true
        c.audioDevices.firstOrNull { it.type == AudioDevice.Type.Speaker && it.hasCapability(AudioDevice.Capabilities.CapabilityPlay) }
            ?.let { call.outputAudioDevice = it }
        c.audioDevices.firstOrNull { it.type == AudioDevice.Type.Microphone && it.hasCapability(AudioDevice.Capabilities.CapabilityRecord) }
            ?.let { call.inputAudioDevice = it }
        Log.i(TAG, "audio routed: out=${call.outputAudioDevice?.deviceName} in=${call.inputAudioDevice?.deviceName}")
    }

    // ---- SIP ----------------------------------------------------------------------

    private fun startSip() {
        pendingInvite = true
        val c = core ?: createCore().also { core = it }
        val acc = c.defaultAccount
        if (acc != null && acc.state == RegistrationState.Ok) {
            placeSipCall()
        } else {
            Status.set("Connecting to SIP server...")
        }
    }

    private fun placeSipCall() {
        val c = core ?: return
        pendingInvite = false
        val target = "sip:${Prefs.target(this)}@${Prefs.domain(this)}"
        val addr = c.interpretUrl(target) ?: run {
            Status.set("Bad target address")
            return
        }
        val params = c.createCallParams(null) ?: return
        params.isVideoEnabled = false
        callerNumber?.let { params.addCustomHeader("X-Caller", it) }
        sipCall = c.inviteAddressWithParams(addr, params)
        Log.i(TAG, "INVITE $target")
        main.postDelayed(ringTimeout, RING_TIMEOUT_MS)
    }

    private fun createCore(): Core {
        val factory = Factory.instance()
        factory.setDebugMode(true, "CallFwdSDK")
        val c = factory.createCore(null, null, this)

        c.isEchoCancellationEnabled = true
        c.mediaEncryption = MediaEncryption.None
        c.isVideoCaptureEnabled = false
        c.isVideoDisplayEnabled = false
        c.setUserAgent("CallForwarder", "0.1.0")
        c.verifyServerCertificates(true)
        c.verifyServerCn(true)

        // Only voice codecs Asterisk is configured for.
        for (pt in c.audioPayloadTypes) {
            pt.enable(pt.mimeType.equals("G722", true) || pt.mimeType.equals("PCMU", true) || pt.mimeType.equals("PCMA", true))
        }

        val domain = Prefs.domain(this)
        val user = Prefs.username(this)
        val auth = factory.createAuthInfo(user, null, Prefs.password(this), null, null, domain)
        c.addAuthInfo(auth)

        val params = c.createAccountParams()
        params.identityAddress = factory.createAddress("sip:$user@$domain")
        val server = factory.createAddress("sip:$domain:${Prefs.port(this)}")
        server?.transport = TransportType.Tls
        params.serverAddress = server
        params.isRegisterEnabled = true
        params.expires = 300
        val account = c.createAccount(params)
        c.addAccount(account)
        c.defaultAccount = account

        c.addListener(listener)
        c.start()
        Log.i(TAG, "core started, registering as $user@$domain")
        return c
    }

    // ---- power / wifi locks -------------------------------------------------------

    // Wi-Fi power-save batches outgoing UDP packets, which made the microphone audio arrive in bursts.
    private fun acquireLocks() {
        if (wifiLock == null) {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val mode = if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY else WifiManager.WIFI_MODE_FULL_HIGH_PERF
            wifiLock = wm.createWifiLock(mode, "CallFwd:wifi").apply { setReferenceCounted(false); acquire() }
        }
        if (cpuLock == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            cpuLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CallFwd:cpu").apply { setReferenceCounted(false); acquire(10 * 60 * 1000L) }
        }
    }

    private fun releaseLocks() {
        wifiLock?.takeIf { it.isHeld }?.release(); wifiLock = null
        cpuLock?.takeIf { it.isHeld }?.release(); cpuLock = null
    }

    // ---- foreground notification --------------------------------------------------

    private fun startForegroundCompat() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Call bridge", NotificationManager.IMPORTANCE_LOW))
        }
        val n: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Call Forwarder")
            .setContentText("Bridging a call to the iPhone")
            .setSmallIcon(android.R.drawable.sym_call_incoming)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(1, n)
        }
    }
}
