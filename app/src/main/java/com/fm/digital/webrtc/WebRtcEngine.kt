package com.fm.digital.webrtc

import android.content.Context
import com.fm.digital.ui.Logger
import org.webrtc.*
import org.webrtc.audio.JavaAudioDeviceModule
import org.webrtc.BuiltinAudioEncoderFactoryFactory
import org.webrtc.BuiltinAudioDecoderFactoryFactory

class WebRtcEngine(private val context: Context) {
    private val tag = "WebRtcEngine"

    private var factory: PeerConnectionFactory? = null
    private var audioSource: AudioSource? = null
    private var localAudioTrack: AudioTrack? = null

    init {
        initializeFactory()
    }

    private fun initializeFactory() {
        val options = PeerConnectionFactory.InitializationOptions.builder(context)
            .createInitializationOptions()
        PeerConnectionFactory.initialize(options)

        val audioDeviceModule = JavaAudioDeviceModule.builder(context)
            .setUseHardwareAcousticEchoCanceler(false)
            .setUseHardwareNoiseSuppressor(false)
            .setAudioSource(android.media.MediaRecorder.AudioSource.MIC)
            .createAudioDeviceModule()

        val audioEncoderFactory = BuiltinAudioEncoderFactoryFactory()
        val audioDecoderFactory = BuiltinAudioDecoderFactoryFactory()

        val factoryOptions = PeerConnectionFactory.Options()

        factory = PeerConnectionFactory.builder()
            .setOptions(factoryOptions)
            .setAudioDeviceModule(audioDeviceModule)
            .setAudioEncoderFactoryFactory(audioEncoderFactory)
            .setAudioDecoderFactoryFactory(audioDecoderFactory)
            .createPeerConnectionFactory()
        
        Logger.log("$tag: PeerConnectionFactory initialized")
    }

    fun startLocalAudio(): AudioTrack? {
        if (factory == null) {
            Logger.log("$tag: Error: Factory not initialized")
            return null
        }

        val audioConstraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "false"))
            mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "false"))
            mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "false"))
            mandatory.add(MediaConstraints.KeyValuePair("googHighpassFilter", "false"))
        }

        audioSource = factory?.createAudioSource(audioConstraints)
        localAudioTrack = factory?.createAudioTrack("ARDM_AUDIO_V1", audioSource)
        localAudioTrack?.setEnabled(true)
        
        Logger.log("$tag: Local audio track created and enabled")
        return localAudioTrack
    }

    fun createPeerConnection(
        rtcConfig: PeerConnection.RTCConfiguration,
        observer: PeerConnection.Observer
    ): PeerConnection? {
        return factory?.createPeerConnection(rtcConfig, observer)
    }

    fun getFactory(): PeerConnectionFactory? = factory

    fun getRtpSenderCapabilities(type: MediaStreamTrack.MediaType): RtpCapabilities? {
        return factory?.getRtpSenderCapabilities(type)
    }

    fun getRtpReceiverCapabilities(type: MediaStreamTrack.MediaType): RtpCapabilities? {
        return factory?.getRtpReceiverCapabilities(type)
    }

    fun release() {
        localAudioTrack?.dispose()
        audioSource?.dispose()
        factory?.dispose()
        Logger.log("$tag: Engine released")
    }
}
