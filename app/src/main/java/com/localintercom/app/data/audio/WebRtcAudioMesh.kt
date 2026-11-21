package com.localintercom.app.data.audio

import android.content.Context
import com.localintercom.app.data.network.RoomProtocol
import com.localintercom.app.domain.model.RoomMember
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.audio.JavaAudioDeviceModule
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
    private val sendSignal: suspend (toMemberId: String, signalType: String, data: JSONObject) -> Unit,
    private val onStatusChanged: (connectedPeers: Int) -> Unit
) {
    private val appContext = context.applicationContext
    private val peers = ConcurrentHashMap<String, Peer>()
    private val knownMembers = ConcurrentHashMap<String, RoomMember>()
    private val factory: PeerConnectionFactory
    private val audioDeviceModule: JavaAudioDeviceModule
    private val audioSource: AudioSource
    private val localAudioTrack: AudioTrack
    private val incomingSignals = Channel<IncomingSignal>(Channel.UNLIMITED)
    private val outgoingSignals = Channel<OutgoingSignal>(Channel.UNLIMITED)
    private var incomingWorker: Job? = null
    private var outgoingWorker: Job? = null
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
        incomingWorker = scope.launch(Dispatchers.IO) {
            for (signal in incomingSignals) {
                if (!closed) processSignal(signal)
            }
        }
        outgoingWorker = scope.launch(Dispatchers.IO) {
            for (signal in outgoingSignals) {
                if (!closed) {
                    runCatching { sendSignal(signal.toMemberId, signal.signalType, signal.data) }
                }
            }
        }
    }

    fun syncMembers(members: List<RoomMember>, activeChannelId: String) {
        if (closed) return
        selectedChannelId = activeChannelId
        val remoteMembers = members.filter { it.id != localMemberId }
        knownMembers.clear()
        remoteMembers.forEach { knownMembers[it.id] = it }
        val remoteIds = remoteMembers.mapTo(mutableSetOf()) { it.id }

        peers.keys.toList().filterNot(remoteIds::contains).forEach(::removePeer)
        remoteMembers.forEach { member ->
            val peer = peers.computeIfAbsent(member.id) { createPeer(member) }
            peer.member = member
            peer.remoteAudioTrack?.setEnabled(member.channelId == selectedChannelId)
        }
        onStatusChanged(peers.values.count { it.connected })
    }

    fun memberRejoined(memberId: String) {
        if (closed || memberId == localMemberId) return
        val member = knownMembers[memberId] ?: return
        removePeer(memberId)
        peers[memberId] = createPeer(member)
        onStatusChanged(peers.values.count { it.connected })
    }

    fun setPttActive(active: Boolean) {
        if (closed) return
        pttActive = active
        localAudioTrack.setEnabled(active)
    }

    fun handleSignal(fromMemberId: String, signalType: String, data: JSONObject) {
        if (closed || fromMemberId == localMemberId) return
        incomingSignals.trySend(
            IncomingSignal(fromMemberId, signalType, JSONObject(data.toString()))
        )
    }

    private suspend fun processSignal(signal: IncomingSignal) {
        val peer = peers[signal.fromMemberId] ?: return
        try {
            when (signal.signalType) {
                RoomProtocol.SIGNAL_OFFER -> {
                    val description = SessionDescription(
                        SessionDescription.Type.OFFER,
                        signal.data.optString("sdp")
                    )
                    setRemoteDescription(peer.connection, description)
                    peer.remoteDescriptionSet = true
                    flushPendingCandidates(peer)
                    val answer = createAnswer(peer.connection)
                    setLocalDescription(peer.connection, answer)
                    queueSignal(
                        signal.fromMemberId,
                        RoomProtocol.SIGNAL_ANSWER,
                        JSONObject().put("sdp", answer.description)
                    )
                }
                RoomProtocol.SIGNAL_ANSWER -> {
                    val description = SessionDescription(
                        SessionDescription.Type.ANSWER,
                        signal.data.optString("sdp")
                    )
                    setRemoteDescription(peer.connection, description)
                    peer.remoteDescriptionSet = true
                    flushPendingCandidates(peer)
                }
                RoomProtocol.SIGNAL_ICE -> {
                    val candidateSdp = signal.data.optString("candidate")
                    if (!HOST_CANDIDATE_PATTERN.containsMatchIn(candidateSdp)) return
                    val candidate = IceCandidate(
                        signal.data.optString("sdpMid"),
                        signal.data.optInt("sdpMLineIndex"),
                        candidateSdp
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

    private fun queueSignal(toMemberId: String, signalType: String, data: JSONObject) {
        if (!closed) outgoingSignals.trySend(OutgoingSignal(toMemberId, signalType, data))
    }

    private fun createPeer(member: RoomMember): Peer {
        val configuration = PeerConnection.RTCConfiguration(emptyList()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            iceTransportsType = PeerConnection.IceTransportsType.ALL
            candidateNetworkPolicy = PeerConnection.CandidateNetworkPolicy.LOW_COST
            networkPreference = PeerConnection.AdapterType.WIFI
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
                if (!HOST_CANDIDATE_PATTERN.containsMatchIn(candidate.sdp)) return
                queueSignal(
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
        queueSignal(
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
        incomingSignals.close()
        outgoingSignals.close()
        incomingWorker?.cancel()
        outgoingWorker?.cancel()
        peers.keys.toList().forEach(::removePeer)
        localAudioTrack.dispose()
        audioSource.dispose()
        factory.dispose()
        audioDeviceModule.release()
    }

    private companion object {
        val HOST_CANDIDATE_PATTERN = Regex("""\btyp\s+host\b""")
    }

    private data class IncomingSignal(
        val fromMemberId: String,
        val signalType: String,
        val data: JSONObject
    )

    private data class OutgoingSignal(
        val toMemberId: String,
        val signalType: String,
        val data: JSONObject
    )

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
