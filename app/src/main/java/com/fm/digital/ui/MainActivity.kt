package com.fm.digital.ui

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.os.IBinder
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.switchmaterial.SwitchMaterial
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.fm.digital.R
import com.fm.digital.networking.ConnectionState
import com.fm.digital.service.BroadcastService
import com.fm.digital.service.ConnectionQuality
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private val viewModel: ConnectionViewModel by viewModels()

    private lateinit var statusTextView: TextView
    private lateinit var connectButton: Button
    private lateinit var disconnectButton: Button
    private lateinit var serverUrlEditText: EditText
    private lateinit var roomIdEditText: EditText
    private lateinit var peerIdEditText: EditText
    private lateinit var versionTextView: TextView
    private lateinit var logTextView: TextView
    private lateinit var vuMeter: ProgressBar
    private lateinit var muteButton: FloatingActionButton
    private lateinit var studioVuMeter: ProgressBar
    private lateinit var rttText: TextView
    private lateinit var monitorSwitch: SwitchMaterial

    private var broadcastService: BroadcastService? = null
    private var serviceBound = false

    // Audio Focus
    private lateinit var audioManager: AudioManager
    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                Logger.i("Audio focus gained. Setting mode to Communication.")
                audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            }
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                Logger.w("Audio focus lost. Will attempt to regain.")
                // The system will call this again with AUDIOFOCUS_GAIN when focus is available
            }
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as BroadcastService.LocalBinder
            broadcastService = binder.getService()
            serviceBound = true

            broadcastService?.onServiceDestroyed = {
                viewModel.disconnect()
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            broadcastService = null
            serviceBound = false
        }
    }

    private val requestMultiplePermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions[Manifest.permission.RECORD_AUDIO] == true &&
            permissions[Manifest.permission.MODIFY_AUDIO_SETTINGS] == true &&
            permissions[Manifest.permission.READ_PHONE_STATE] == true) {
            startFullProcess()
        } else {
            Toast.makeText(this, "Microphone, audio settings, and phone state permissions are required for broadcasting", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        initializeViews()
        Logger.initialize(applicationContext, logTextView)
        Logger.i("App started - Version 2.0 Stable")

        setupClickListeners()
        observeConnectionState()
        observeAudioLevels()
        observeMuteState()
        observeNetworkStats()
        observeStudioAudioLevels()
    }

    private fun initializeViews() {
        statusTextView = findViewById(R.id.status_textview)
        connectButton = findViewById(R.id.connect_button)
        disconnectButton = findViewById(R.id.disconnect_button)
        serverUrlEditText = findViewById(R.id.server_url_edittext)
        roomIdEditText = findViewById(R.id.room_id_edittext)
        peerIdEditText = findViewById(R.id.peer_id_edittext)
        versionTextView = findViewById(R.id.version_textview)
        logTextView = findViewById(R.id.log_textview)
        vuMeter = findViewById(R.id.vuMeter)
        muteButton = findViewById(R.id.mute_button)
        studioVuMeter = findViewById(R.id.studioVuMeter)
        rttText = findViewById(R.id.rttText)
        monitorSwitch = findViewById(R.id.monitorSwitch)

        versionTextView.text = "Version: 2.0 - Stable Broadcast (2+ hours)"
    }

    private fun setupClickListeners() {
        connectButton.setOnClickListener {
            checkPermissionAndStart()
        }

        disconnectButton.setOnClickListener {
            stopBroadcastService()
            viewModel.disconnect()
            resetAudioManager()
        }

        muteButton.setOnClickListener {
            viewModel.toggleMute()
        }

        // Monitor switch for studio feed
        monitorSwitch.setOnCheckedChangeListener { _, isChecked ->
            broadcastService?.let { service ->
                if (service.isMediasoupInitialized) {
                    service.mediasoupManager.setPlaybackEnabled(isChecked)
                    
                    // Safety: Warn if using earpiece for monitoring
                    if (isChecked && !isHeadsetPlugged()) {
                        Toast.makeText(this, "Caution: Using Earpiece for Monitor", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private fun observeConnectionState() {
        lifecycleScope.launch {
            viewModel.connectionState.collect { state ->
                updateConnectionStatus(state)
                updateServiceNotification(state)
            }
        }
    }

    private fun observeAudioLevels() {
        lifecycleScope.launch {
            viewModel.audioLevels.collect { level ->
                vuMeter.progress = level
            }
        }
    }

    private fun observeMuteState() {
        lifecycleScope.launch {
            viewModel.isMuted.collect { isMuted ->
                if (isMuted) {
                    muteButton.setImageResource(R.drawable.ic_mic_off)
                } else {
                    muteButton.setImageResource(R.drawable.ic_mic_on)
                }
            }
        }
    }

    private fun observeNetworkStats() {
        lifecycleScope.launch {
            broadcastService?.networkStats?.collect { stats ->
                rttText.text = stats
            }
        }
    }

    private fun observeStudioAudioLevels() {
        lifecycleScope.launch {
            broadcastService?.inboundAudioLevel?.collect { level ->
                studioVuMeter.progress = level
            }
        }
    }

    private fun isHeadsetPlugged(): Boolean {
        return audioManager.isWiredHeadsetOn
    }

    private fun checkPermissionAndStart() {
        val permissionsToRequest = arrayOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.MODIFY_AUDIO_SETTINGS,
            Manifest.permission.READ_PHONE_STATE
        ).filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (permissionsToRequest.isEmpty()) {
            startFullProcess()
        } else {
            requestMultiplePermissionsLauncher.launch(permissionsToRequest.toTypedArray())
        }
    }

    private fun startFullProcess() {
        val serverUrl = serverUrlEditText.text.toString()
        val roomId = roomIdEditText.text.toString()
        val peerId = peerIdEditText.text.toString()

        if (serverUrl.isBlank() || roomId.isBlank() || peerId.isBlank()) {
            Toast.makeText(this, "Please fill all fields", Toast.LENGTH_SHORT).show()
            return
        }

        setupAudioManager()
        startBroadcastService(serverUrl)
        viewModel.connect(serverUrl, roomId, peerId)
    }

    private fun setupAudioManager() {
        val result = audioManager.requestAudioFocus(
            audioFocusChangeListener,
            AudioManager.STREAM_VOICE_CALL,
            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
        )

        if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            Logger.i("Audio focus request granted. Setting mode to Communication.")
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        } else {
            Logger.e("Audio focus request failed.")
            Toast.makeText(this, "Could not get audio focus", Toast.LENGTH_SHORT).show()
        }
    }

    private fun resetAudioManager() {
        audioManager.abandonAudioFocus(audioFocusChangeListener)
        audioManager.mode = AudioManager.MODE_NORMAL
        Logger.i("Audio focus abandoned and mode reset to Normal.")
    }

    private fun startBroadcastService(serverUrl: String) {
        val intent = Intent(this, BroadcastService::class.java).apply {
            putExtra("serverUrl", serverUrl)
        }

        startForegroundService(intent)

        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

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
                    // pauseBroadcastLocally()
                }
                AudioManager.AUDIOFOCUS_GAIN -> {
                    Logger.log("Mic regained. Resuming broadcast.")
                    // resumeBroadcastLocally()
                }
            }
        }
        .build()

    fun requestFocus() {
        val result = audioManager.requestAudioFocus(focusRequest)
        if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        }
    }



    private fun stopBroadcastService() {
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }

        val intent = Intent(this, BroadcastService::class.java)
        stopService(intent)
        broadcastService = null
    }

    private fun updateServiceNotification(state: ConnectionState) {
        when (state) {
            ConnectionState.CONNECTING ->
                broadcastService?.updateNotification("Connecting to server...", ConnectionQuality.WARNING)
            ConnectionState.CONNECTED ->
                broadcastService?.updateNotification("🔴 LIVE - On Air", ConnectionQuality.EXCELLENT)
            ConnectionState.DISCONNECTED ->
                broadcastService?.updateNotification("Disconnected", ConnectionQuality.DISCONNECTED)
            ConnectionState.ERROR ->
                broadcastService?.updateNotification("Connection Error - Retrying...", ConnectionQuality.POOR)
        }
    }

    private fun updateConnectionStatus(connectionState: ConnectionState) {
        val (text, color) = when (connectionState) {
            ConnectionState.DISCONNECTED -> "Disconnected" to android.R.color.holo_red_dark
            ConnectionState.CONNECTING -> "Connecting..." to android.R.color.holo_orange_dark
            ConnectionState.CONNECTED -> "🔴 LIVE - Streaming" to android.R.color.holo_green_dark
            ConnectionState.ERROR -> "Error - Reconnecting..." to android.R.color.holo_red_light
        }
        statusTextView.text = text
        statusTextView.setTextColor(resources.getColor(color, theme))

        connectButton.isEnabled = connectionState == ConnectionState.DISCONNECTED
        disconnectButton.isEnabled = connectionState != ConnectionState.DISCONNECTED
    }

    override fun onDestroy() {
        super.onDestroy()
        if (serviceBound) {
            unbindService(serviceConnection)
        }
        resetAudioManager()
        Logger.close()
    }
}
