package com.fm.digital.service

import android.Manifest
import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.fm.digital.R
import com.fm.digital.networking.ConnectionState
import com.fm.digital.networking.SignalingClient
import com.fm.digital.networking.SignalingClientImpl
import com.fm.digital.networking.SignalingMessage
import com.fm.digital.ui.Logger
import com.fm.digital.webrtc.MediasoupManager
import com.fm.digital.webrtc.WebRtcEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

enum class ConnectionQuality {
    EXCELLENT,
    GOOD,
    WARNING,
    POOR,
    DISCONNECTED
}

class BroadcastService : Service() {

    private val binder = LocalBinder()
    private var wakeLock: PowerManager.WakeLock? = null

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)

    private lateinit var webRtcEngine: WebRtcEngine
    private lateinit var signalingClient: SignalingClient
    lateinit var mediasoupManager: MediasoupManager
        private set

    val isMediasoupInitialized: Boolean
        get() = ::mediasoupManager.isInitialized

    private val _broadcastState = MutableStateFlow<BroadcastState>(BroadcastState.Idle)
    val broadcastState: StateFlow<BroadcastState> = _broadcastState.asStateFlow()

    var onServiceDestroyed: (() -> Unit)? = null

    private lateinit var audioManager: AudioManager

    // Expose network stats from MediasoupManager
    private val _emptyNetworkStats = MutableStateFlow("")
    val networkStats: StateFlow<String>
        get() = if (isMediasoupInitialized) mediasoupManager.networkStats else _emptyNetworkStats
    
    // Expose inbound audio level for studio VU meter
    private val _emptyAudioLevel = MutableStateFlow(0)
    val inboundAudioLevel: StateFlow<Int>
        get() = if (isMediasoupInitialized) mediasoupManager.inboundAudioLevel else _emptyAudioLevel

    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        .setAcceptsDelayedFocusGain(true)
        .setOnAudioFocusChangeListener { focusChange ->
            when (focusChange) {
                AudioManager.AUDIOFOCUS_LOSS,
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                    // Mic hijacked by a call or another app!
                    Logger.log("Mic Hijacked! Muting stream to prevent crash.")
                    pauseBroadcastLocally()
                }
                AudioManager.AUDIOFOCUS_GAIN -> {
                    Logger.log("Mic regained. Resuming broadcast.")
                    resumeBroadcastLocally()
                }
            }
        }
        .build()

    private val telephonyManager by lazy { 
        getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager 
    }

    private var phoneStateListener: Any? = null

    private val headsetReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_HEADSET_PLUG) {
                val state = intent.getIntExtra("state", -1)
                when (state) {
                    0 -> { // HEADSET UNPLUGGED
                        Logger.log("SAFETY: Unplugged. Forcing EARPIECE.")
                        applyEmergencyRouting(toEarpiece = true)
                    }
                    1 -> { // HEADSET PLUGGED
                        Logger.log("SAFETY: Headset detected. Routing to HEADPHONES.")
                        applyEmergencyRouting(toEarpiece = false)
                    }
                }
            }
        }
    }

    inner class LocalBinder : Binder() {
        fun getService(): BroadcastService = this@BroadcastService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        createNotificationChannel()
        acquireWakeLock()
        registerPhoneStateListener()
        setupHeadsetListener()
    }

    private fun initializeManagers(serverUrl: String) {
        webRtcEngine = WebRtcEngine(this)
        signalingClient = SignalingClientImpl(serverUrl, scope)
        mediasoupManager = MediasoupManager(webRtcEngine, signalingClient, scope)

        observeSignalingState()
        observeMediasoupEvents()
    }

    private fun observeSignalingState() {
        scope.launch {
            signalingClient.connectionState.collect { state ->
                when (state) {
                    ConnectionState.CONNECTING -> {
                        _broadcastState.value = BroadcastState.Connecting
                        updateNotification("Connecting to server...", ConnectionQuality.DISCONNECTED)
                    }
                    ConnectionState.CONNECTED -> {
                        _broadcastState.value = BroadcastState.Broadcasting("Connected to server", ConnectionQuality.EXCELLENT)
                        updateNotification("Connected, waiting for audio stream...", ConnectionQuality.GOOD)
                    }
                    ConnectionState.DISCONNECTED -> {
                        _broadcastState.value = BroadcastState.Idle
                        updateNotification("Disconnected", ConnectionQuality.DISCONNECTED)
                    }
                    ConnectionState.ERROR -> {
                        _broadcastState.value = BroadcastState.Error("Connection error")
                        updateNotification("Connection error", ConnectionQuality.POOR)
                    }
                }
            }
        }
    }

    private fun observeMediasoupEvents() {
        scope.launch {
            mediasoupManager.producerId.collect { producerId ->
                if (producerId != null) {
                    _broadcastState.value = BroadcastState.Broadcasting("Streaming live audio", ConnectionQuality.EXCELLENT)
                    updateNotification("On Air: Streaming live!", ConnectionQuality.EXCELLENT)
                } else if (signalingClient.connectionState.value == ConnectionState.CONNECTED) {
                    _broadcastState.value = BroadcastState.Broadcasting("Connected to server", ConnectionQuality.GOOD)
                    updateNotification("Connected, waiting for audio stream...", ConnectionQuality.GOOD)
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val serverUrl = intent?.getStringExtra("serverUrl")
        if (serverUrl == null) {
            Logger.e("Service: serverUrl is null, stopping service")
            stopSelf()
            return START_NOT_STICKY
        }

        initializeManagers(serverUrl)

        val notification = createNotification("Initiating broadcast...", ConnectionQuality.DISCONNECTED)
        startForeground(NOTIFICATION_ID, notification)
        startBroadcast()
        return START_STICKY
    }

    private fun startBroadcast() {
        requestFocus()
        signalingClient.connect()
        mediasoupManager.start()
        _broadcastState.value = BroadcastState.Connecting
    }

    fun stopBroadcast() {
        Logger.i("Service: stopBroadcast called")
        audioManager.abandonAudioFocusRequest(focusRequest)
        mediasoupManager.close()
        signalingClient.disconnect()
        _broadcastState.value = BroadcastState.Idle
        stopForeground(true)
        stopSelf()
    }

    fun requestFocus() {
        val result = audioManager.requestAudioFocus(focusRequest)
        if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        }
    }

    private fun pauseBroadcastLocally() {
        mediasoupManager.setMicEnabled(false)
        updateNotification("Mic Busy: Call or other app", ConnectionQuality.WARNING)
    }

    private fun resumeBroadcastLocally() {
        mediasoupManager.setMicEnabled(true)
        updateNotification("On Air: Streaming live!", ConnectionQuality.EXCELLENT)
    }

    private fun stopStreamingTemporarily() {
        mediasoupManager.setMicEnabled(false)
        updateNotification("Mic Busy: Phone Call", ConnectionQuality.WARNING)
    }

    private fun attemptRecovery() {
        resumeBroadcastLocally()
    }

    private fun setupHeadsetListener() {
        val filter = IntentFilter(Intent.ACTION_HEADSET_PLUG)
        registerReceiver(headsetReceiver, filter)
    }

    private fun applyEmergencyRouting(toEarpiece: Boolean) {
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val devices = audioManager.availableCommunicationDevices
            val targetType = if (toEarpiece) AudioDeviceInfo.TYPE_BUILTIN_EARPIECE 
                             else AudioDeviceInfo.TYPE_WIRED_HEADSET
            
            val targetDevice = devices.firstOrNull { it.type == targetType }
            targetDevice?.let { audioManager.setCommunicationDevice(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = false
        }
    }

    private fun registerPhoneStateListener() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            Logger.log("BroadcastService: READ_PHONE_STATE permission not granted. Cannot listen to call state.")
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val callback = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                override fun onCallStateChanged(state: Int) {
                    handleCallStateChanged(state)
                }
            }
            telephonyManager.registerTelephonyCallback(mainExecutor, callback)
            phoneStateListener = callback
        } else {
            @Suppress("DEPRECATION")
            val listener = object : PhoneStateListener() {
                override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                    handleCallStateChanged(state)
                }
            }
            @Suppress("DEPRECATION")
            telephonyManager.listen(listener, PhoneStateListener.LISTEN_CALL_STATE)
            phoneStateListener = listener
        }
    }

    private fun unregisterPhoneStateListener() {
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && phoneStateListener is TelephonyCallback -> {
                telephonyManager.unregisterTelephonyCallback(phoneStateListener as TelephonyCallback)
            }
            phoneStateListener is PhoneStateListener -> {
                @Suppress("DEPRECATION")
                telephonyManager.listen(phoneStateListener as PhoneStateListener, PhoneStateListener.LISTEN_NONE)
            }
        }
        phoneStateListener = null
    }

    private fun handleCallStateChanged(state: Int) {
        when (state) {
            TelephonyManager.CALL_STATE_RINGING,
            TelephonyManager.CALL_STATE_OFFHOOK -> {
                Logger.log("Telecom Intervention: Interrupting broadcast for phone call.")
                signalingClient.send("error", SignalingMessage.Error("Mic hijacked by phone call"))
                stopStreamingTemporarily()
            }
            TelephonyManager.CALL_STATE_IDLE -> {
                attemptRecovery()
            }
        }
    }

    fun updateNotification(status: String, quality: ConnectionQuality) {
        val notification = createNotification(status, quality)
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun createNotification(status: String, quality: ConnectionQuality): Notification {
        val intent = Intent(this, com.fm.digital.ui.MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val (icon, color) = when (quality) {
            ConnectionQuality.EXCELLENT -> R.drawable.ic_mic_on to 0xFF4CAF50.toInt()
            ConnectionQuality.GOOD -> R.drawable.ic_mic_on to 0xFF8BC34A.toInt()
            ConnectionQuality.WARNING -> R.drawable.ic_mic_on to 0xFFFF9800.toInt()
            ConnectionQuality.POOR -> R.drawable.ic_mic_off to 0xFFF44336.toInt()
            ConnectionQuality.DISCONNECTED -> R.drawable.ic_mic_off to 0xFF757575.toInt()
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("FM Digital Broadcaster")
            .setContentText(status)
            .setSmallIcon(icon)
            .setColor(color)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Broadcasting Service",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Keeps microphone active during live broadcast"
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun acquireWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "FMDigital::BroadcastWakeLock"
        ).apply {
            acquire()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterPhoneStateListener()
        unregisterReceiver(headsetReceiver)
        job.cancel()
        wakeLock?.release()
        onServiceDestroyed?.invoke()
        Logger.i("Service: onDestroy")
    }

    companion object {
        private const val CHANNEL_ID = "broadcast_channel"
        private const val NOTIFICATION_ID = 1001
    }
}

sealed class BroadcastState {
    object Idle : BroadcastState()
    object Connecting : BroadcastState()
    data class Broadcasting(val serverInfo: String, val quality: ConnectionQuality) : BroadcastState()
    data class Error(val message: String) : BroadcastState()
}
