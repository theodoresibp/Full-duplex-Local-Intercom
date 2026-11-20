# Audio Engine

## Initial design

- WebRTC peer-to-peer audio mesh.
- Opus codec, mono, 48 kHz.
- Initial target bitrate: 32 kbit/s per outgoing peer stream (to be measured/tuned).
- DTLS-SRTP for encrypted media.
- ICE host candidates for same-LAN connectivity; no internet service dependency.
- Enable platform/WebRTC echo cancellation, noise suppression and gain control where supported and validate on target devices.

## Full-duplex behavior

Every participant may publish at the same time. The local device receives audio from all peers in the active channel and mixes playback locally. PTT gates only the local microphone's outgoing track. There is no single-speaker token or global audio floor.

## Channel isolation

A participant subscribes to the currently selected channel. Switching channels updates track enable/subscription state without tearing down every peer connection where possible. The channel state is authoritative from the current room coordinator and versioned by room epoch.

## Bluetooth and routing

Use Android AudioManager communication-device routing and MediaSession/media-button events. Headset implementations vary: some provide distinct key-down/key-up events, while others emit only a click. Support configurable hold-to-talk when reliable press/release events exist, plus toggle-to-talk as a compatibility mode. Channel switching mappings must be configurable and must not assume every headset exposes multiple buttons.

## Background operation

The active session is owned by a microphone foreground service. Verify behavior with screen off, locked device, Bluetooth reconnect, audio focus changes, incoming calls and OEM battery restrictions. A persistent notification provides an obvious way to return to or stop the session.

## Performance validation

Measure end-to-end latency, packet loss, jitter, CPU, battery and thermal behavior with two through six physical Android devices. Treat 150–250 ms as an initial target, not a guarantee.
