package com.fm.digital.webrtc

import org.webrtc.SessionDescription

interface SdpObserver : org.webrtc.SdpObserver {

    override fun onCreateSuccess(sdp: SessionDescription)

    override fun onSetSuccess()

    override fun onCreateFailure(error: String)

    override fun onSetFailure(error: String)
}
