package com.fm.digital.webrtc

import android.content.Context
import com.fm.digital.ui.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.webrtc.*
import org.webrtc.audio.JavaAudioDeviceModule
import org.webrtc.BuiltinAudioEncoderFactoryFactory
import org.webrtc.BuiltinAudioDecoderFactoryFactory
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.log10
import kotlin.math.sqrt

class WebRtcEngine(private val context: Context) {
    private val tag = "WebRtcEngine"

    private var factory: PeerConnectionFactory? = null
    private var audioSource: AudioSource? = null
    private var localAudioTrack: AudioTrack? = null
    private var audioDeviceModule: JavaAudioDeviceModule? = null

    private val _audioLevels = MutableStateFlow<Int>(0)
    val audioLevels = _audioLevels.asStateFlow()

    private val _isMuted = MutableStateFlow<Boolean>(false)
    val isMuted = _isMuted.asStateFlow()

    init {
        initializeFactory()
    }

    private fun initializeFactory() {
        val fieldTrials = "WebRTC-Audio-Min-Expected-Delay-Ms/0/" + // Force 0ms base delay
                          "WebRTC-Audio-NetEqMaxPrebufMS/60/"     // Cap max buffer at 60ms

        val options = PeerConnectionFactory.InitializationOptions.builder(context)
            .setFieldTrials(fieldTrials) // <--- CRITICAL FOR LATENCY
            .setEnableInternalTracer(true)
            .createInitializationOptions()
        PeerConnectionFactory.initialize(options)

        audioDeviceModule = JavaAudioDeviceModule.builder(context)
            .setUseHardwareAcousticEchoCanceler(true)
            .setUseHardwareNoiseSuppressor(true)
            .setAudioSource(android.media.MediaRecorder.AudioSource.VOICE_COMMUNICATION)
            // Low Latency Fix:
            .setAudioTrackStateCallback(object : JavaAudioDeviceModule.AudioTrackStateCallback {
                override fun onWebRtcAudioTrackStart() { Logger.log("Audio Track Started") }
                override fun onWebRtcAudioTrackStop() { Logger.log("Audio Track Stopped") }
            })
            // TAP INTO THE AUDIO SAMPLES HERE
            .setSamplesReadyCallback { samples ->
                val shortArray = ShortArray(samples.data.size / 2)
                ByteBuffer.wrap(samples.data).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shortArray)
                val level = calculateLevel(shortArray)
                _audioLevels.value = level
            }
            .setUseStereoInput(true)
            .setUseStereoOutput(true)
            .createAudioDeviceModule()

        // Force AAudio via AudioAttributes
        audioDeviceModule?.setMicrophoneMute(false)

        val audioEncoderFactory = BuiltinAudioEncoderFactoryFactory()
        val audioDecoderFactory = BuiltinAudioDecoderFactoryFactory()

        val factoryOptions = PeerConnectionFactory.Options()

        factory = PeerConnectionFactory.builder()
            .setOptions(factoryOptions)
            .setAudioDeviceModule(audioDeviceModule)
            .setAudioEncoderFactoryFactory(audioEncoderFactory)
            .setAudioDecoderFactoryFactory(audioDecoderFactory)
            .createPeerConnectionFactory()
        
        Logger.i("$tag: PeerConnectionFactory initialized")
    }

    fun setMicrophoneMute(isMuted: Boolean) {
        audioDeviceModule?.setMicrophoneMute(isMuted)
        _isMuted.value = isMuted
    }

    private fun calculateLevel(samples: ShortArray): Int {
        var sum = 0.0
        if (samples.isEmpty()) return 0
        
        for (sample in samples) {
            sum += (sample.toDouble() * sample.toDouble())
        }

        val rms = sqrt(sum / samples.size)
        if (rms <= 0.0) return 0

        // Convert to dB. Reference level for 16-bit audio is 32768
        val db = 20 * log10(rms / 32768.0)

        // Map dB range (-60 dB to 0 dB) to 0-100 range
        val minDb = -60.0
        var level = ((db - minDb) * (100.0 / -minDb)).toInt()

        return level.coerceIn(0, 100)
    }

    fun startLocalAudio(): AudioTrack? {
        if (factory == null) {
            Logger.e("$tag: Error: Factory not initialized")
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
        
        Logger.i("$tag: Local audio track created and enabled")
        return localAudioTrack
    }

    fun getLocalAudioTrack(): AudioTrack? = localAudioTrack

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
        audioDeviceModule?.release()
        Logger.i("$tag: Engine released")
    }
}
