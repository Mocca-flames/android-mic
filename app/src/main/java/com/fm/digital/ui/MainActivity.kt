package com.fm.digital.ui

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.IBinder
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.fm.digital.R
import com.fm.digital.networking.ConnectionState
import com.fm.digital.service.BroadcastService
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

    private var broadcastService: BroadcastService? = null
    private var serviceBound = false

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

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            startFullProcess()
        } else {
            Toast.makeText(this, "Microphone permission required for broadcasting", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initializeViews()
        Logger.initialize(applicationContext, logTextView)
        Logger.log("App started - Version 2.0 Stable")

        setupClickListeners()
        observeConnectionState()
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

        versionTextView.text = "Version: 2.0 - Stable Broadcast (2+ hours)"
    }

    private fun setupClickListeners() {
        connectButton.setOnClickListener {
            checkPermissionAndStart()
        }

        disconnectButton.setOnClickListener {
            stopBroadcastService()
            viewModel.disconnect()
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

    private fun checkPermissionAndStart() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED) {
            startFullProcess()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
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

        startBroadcastService()
        viewModel.connect(serverUrl, roomId, peerId)
    }

    private fun startBroadcastService() {
        val intent = Intent(this, BroadcastService::class.java)

        startForegroundService(intent)

        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
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
                broadcastService?.updateNotification("Connecting to server...", false)
            ConnectionState.CONNECTED ->
                broadcastService?.updateNotification("🔴 LIVE - On Air", true)
            ConnectionState.DISCONNECTED ->
                broadcastService?.updateNotification("Disconnected", false)
            ConnectionState.ERROR ->
                broadcastService?.updateNotification("Connection Error - Retrying...", false)
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
        Logger.close()
    }
}