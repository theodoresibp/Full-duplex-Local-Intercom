package com.localintercom.app.data.audio

import android.content.Context
import com.localintercom.app.data.network.RoomProtocol
import com.localintercom.app.domain.model.RoomMember
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.JavaAudioDeviceModule
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * One direct WebRTC connection per remote room member. The room host only relays
 * signaling; audio is never sent through the Ktor room server.
 */
internal class WebRtcAudioMesh(
    context: Context,
    private val scope: CoroutineScope,
    private val localMemberId: String,
    private val sendSignal: (toMemberId: String, signalType: String, data: JSONObject) -> Unit,
    private val onStatusChanged: (connectedPeers: Int) -> Unit
) {
    private val appContext = context.applicationContext
    private val peers = ConcurrentHashMap<String, Peer>()
    private val factory: PeerConnectionFactory
    private val audioDeviceModule: JavaAudioDeviceModule
    private val audioSource: AudioSource
    private val localAudioTrack: AudioTrack
    @Volatile private var selectedChannelId: String = "general"
    @Volatile private var pttActive: Boolean = false
    @Volatile private var closed: Boolean = false

    init {
        WebRtcRuntime.initialize(appContext)
        audioDeviceModule = JavaAudioDeviceModule.builder(appContext)
            .setUseHardwareAcousticEchoCanceler(true)
            .setUseHardwareNoiseSuppressor(true)
            .createAudioDeviceModule()
        factory = PeerConnectionFactory.builder()
            .setAudioDeviceModule(audioDeviceModule)
            .createPeerConnectionFactory()
        audioSource = factory.createAudioSource(MediaConstraints())
        localAudioTrack = factory.createAudioTrack("local-audio-$localMemberId", audioSource).apply {
            setEnabled(false)
        }
    }

    fun syncMembers(members: List<RoomMember>, activeChannelId: String) {
        if (closed) return
        selectedChannelId = activeChannelId
        val remoteMembers = members.filter { it.id != localMemberId }
        val remoteIds = remoteMembers.mapTo(mutableSetOf()) { it.id }

        peers.keys.toList().filterNot(remoteIds::contains).forEach(::removePeer)
        remoteMembers.forEach { member ->
            val peer = peers.computeIfAbsent(member.id) { createPeer(member) }
            peer.member = member
            peer.remoteAudioTrack?.setEnabled(member.channelId == selectedChannelId)
        }
        onStatusChanged(peers.values.count { it.connected })
    }

    fun setPttActive(active: Boolean) {
        if (closed) return
        pttActive = active
        localAudioTrack.setEnabled(active)
    }

    fun handleSignal(fromMemberId: String, signalType: String, data: JSONObject) {
        if (closed || fromMemberId == localMemberId) return
        scope.launch(Dispatchers.IO) {
            val peer = peers[fromMemberId] ?: return@launch
            try {
                when (signalType) {
                    RoomProtocol.SIGNAL_OFFER -> {
                        val description = SessionDescription(
                            SessionDescription.Type.OFFER,
                            data.optString("sdp")
                        )
                        setRemoteDescription(peer.connection, description)
                        peer.remoteDescriptionSet = true
                        flushPendingCandidates(peer)
                        val answer = createAnswer(peer.connection)
                        setLocalDescription(peer.connection, answer)
                        sendSignal(
                            fromMemberId,
                            RoomProtocol.SIGNAL_ANSWER,
                            JSONObject().put("sdp", answer.description)
                        )
                    }
                    RoomProtocol.SIGNAL_ANSWER -> {
                        val description = SessionDescription(
                            SessionDescription.Type.ANSWER,
                            data.optString("sdp")
                        )
                        setRemoteDescription(peer.connection, description)
                        peer.remoteDescriptionSet = true
                        flushPendingCandidates(peer)
                    }
                    RoomProtocol.SIGNAL_ICE -> {
                        val candidate = IceCandidate(
                            data.optString("sdpMid"),
                            data.optInt("sdpMLineIndex"),
                            data.optString("candidate")
                        )
                        if (peer.remoteDescriptionSet) peer.connection.addIceCandidate(candidate)
                        else peer.pendingCandidates += candidate
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                // A peer can disappear during negotiation; its next roster snapshot recreates it.
            }
        }
    }

    private fun createPeer(member: RoomMember): Peer {
        val configuration = PeerConnection.RTCConfiguration(emptyList()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            iceTransportsType = PeerConnection.IceTransportsType.ALL
        }
        lateinit var peer: Peer
        val observer = object : PeerConnection.Observer {
            override fun onSignalingChange(newState: PeerConnection.SignalingState) = Unit
            override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState) {
                peer.connected = newState == PeerConnection.IceConnectionState.CONNECTED ||
                    newState == PeerConnection.IceConnectionState.COMPLETED
                onStatusChanged(peers.values.count { it.connected })
            }
            override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
            override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState) = Unit
            override fun onIceCandidate(candidate: IceCandidate) {
                sendSignal(
                    member.id,
                    RoomProtocol.SIGNAL_ICE,
                    JSONObject()
                        .put("sdpMid", candidate.sdpMid)
                        .put("sdpMLineIndex", candidate.sdpMLineIndex)
                        .put("candidate", candidate.sdp)
                )
            }
            override fun onIceCandidatesRemoved(candidates: Array<IceCandidate>) = Unit
            override fun onAddStream(stream: MediaStream) = Unit
            override fun onRemoveStream(stream: MediaStream) = Unit
            override fun onDataChannel(dataChannel: DataChannel) = Unit
            override fun onRenegotiationNeeded() = Unit
            override fun onAddTrack(receiver: RtpReceiver, mediaStreams: Array<MediaStream>) {
                (receiver.track() as? AudioTrack)?.let { track ->
                    peer.remoteAudioTrack = track
                    track.setEnabled(peer.member.channelId == selectedChannelId)
                }
            }
            override fun onTrack(transceiver: RtpTransceiver) {
                (transceiver.receiver.track() as? AudioTrack)?.let { track ->
                    peer.remoteAudioTrack = track
                    track.setEnabled(peer.member.channelId == selectedChannelId)
                }
            }
        }
        val connection = factory.createPeerConnection(configuration, observer)
            ?: throw IllegalStateException("Could not create a WebRTC peer connection.")
        peer = Peer(member, connection)
        connection.addTrack(localAudioTrack, listOf("local-intercom"))
        if (localMemberId < member.id) {
            scope.launch(Dispatchers.IO) { negotiateOffer(peer) }
        }
        return peer
    }

    private suspend fun negotiateOffer(peer: Peer) {
        val offer = createOffer(peer.connection)
        setLocalDescription(peer.connection, offer)
        sendSignal(
            peer.member.id,
            RoomProtocol.SIGNAL_OFFER,
            JSONObject().put("sdp", offer.description)
        )
    }

    private suspend fun createOffer(connection: PeerConnection): SessionDescription =
        suspendCancellableCoroutine { continuation ->
            connection.createOffer(object : SdpObserver {
                override fun onCreateSuccess(description: SessionDescription) {
                    if (continuation.isActive) continuation.resume(description)
                }
                override fun onSetSuccess() = Unit
                override fun onCreateFailure(error: String) {
                    if (continuation.isActive) continuation.resumeWithException(IllegalStateException(error))
                }
                override fun onSetFailure(error: String) = Unit
            }, audioConstraints())
        }

    private suspend fun createAnswer(connection: PeerConnection): SessionDescription =
        suspendCancellableCoroutine { continuation ->
            connection.createAnswer(object : SdpObserver {
                override fun onCreateSuccess(description: SessionDescription) {
                    if (continuation.isActive) continuation.resume(description)
                }
                override fun onSetSuccess() = Unit
                override fun onCreateFailure(error: String) {
                    if (continuation.isActive) continuation.resumeWithException(IllegalStateException(error))
                }
                override fun onSetFailure(error: String) = Unit
            }, audioConstraints())
        }

    private suspend fun setLocalDescription(connection: PeerConnection, description: SessionDescription) {
        suspendCancellableCoroutine { continuation ->
            connection.setLocalDescription(object : SdpObserver {
                override fun onCreateSuccess(description: SessionDescription?) = Unit
                override fun onSetSuccess() {
                    if (continuation.isActive) continuation.resume(Unit)
                }
                override fun onCreateFailure(error: String) = Unit
                override fun onSetFailure(error: String) {
                    if (continuation.isActive) continuation.resumeWithException(IllegalStateException(error))
                }
            }, description)
        }
    }

    private suspend fun setRemoteDescription(connection: PeerConnection, description: SessionDescription) {
        suspendCancellableCoroutine { continuation ->
            connection.setRemoteDescription(object : SdpObserver {
                override fun onCreateSuccess(description: SessionDescription?) = Unit
                override fun onSetSuccess() {
                    if (continuation.isActive) continuation.resume(Unit)
                }
                override fun onCreateFailure(error: String) = Unit
                override fun onSetFailure(error: String) {
                    if (continuation.isActive) continuation.resumeWithException(IllegalStateException(error))
                }
            }, description)
        }
    }

    private fun audioConstraints() = MediaConstraints().apply {
        mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
        mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
    }

    private fun flushPendingCandidates(peer: Peer) {
        peer.pendingCandidates.forEach(peer.connection::addIceCandidate)
        peer.pendingCandidates.clear()
    }

    private fun removePeer(memberId: String) {
        peers.remove(memberId)?.let { peer ->
            peer.remoteAudioTrack?.setEnabled(false)
            peer.connection.close()
            peer.connection.dispose()
        }
        onStatusChanged(peers.values.count { it.connected })
    }

    fun close() {
        if (closed) return
        closed = true
        pttActive = false
        localAudioTrack.setEnabled(false)
        peers.keys.toList().forEach(::removePeer)
        localAudioTrack.dispose()
        audioSource.dispose()
        factory.dispose()
        audioDeviceModule.release()
    }

    private class Peer(
        var member: RoomMember,
        val connection: PeerConnection,
        var connected: Boolean = false,
        var remoteDescriptionSet: Boolean = false,
        var remoteAudioTrack: AudioTrack? = null,
        val pendingCandidates: MutableList<IceCandidate> = mutableListOf()
    )

    private object WebRtcRuntime {
        @Volatile private var initialized = false
        @Synchronized fun initialize(context: Context) {
            if (initialized) return
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(context)
                    .createInitializationOptions()
            )
            initialized = true
        }
    }
}
