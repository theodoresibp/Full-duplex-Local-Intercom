# Audio Engine

## Implemented audio path

- WebRTC Android SDK from Maven Central.
- One PeerConnection per remote participant, up to five connections on a six-person room.
- Mono audio track published directly to every peer; no audio relay or mixer on the room host.
- WebRTC's built-in Opus audio codec and DTLS-SRTP media protection.
- No STUN/TURN servers. Peer connections are configured for same-LAN host candidates and prefer Wi-Fi interfaces.
- Hardware echo cancellation and noise suppression are enabled when supported by the Android device.
- PTT disables the local outgoing audio track until the user presses and holds the on-screen PTT surface or a supported headset media button.

The audio engine is implemented in code but its latency, codec behavior, and multi-device stability have not yet been measured on physical devices.

## Full-duplex behavior

Every participant may publish at the same time. Each device receives audio from all peers and WebRTC handles local playback mixing. PTT gates only the local microphone's outgoing track. There is no single-speaker token or global audio floor.

## Channel isolation

Each member has one active channel ID. The room host validates channel IDs and broadcasts updated member state. Each receiver enables remote audio tracks only when that sender's channel matches the local selected channel. The five default channel IDs are General, Team A, Team B, Security, and Emergency.

This is receiver-side isolation, not cryptographic per-channel access control: audio packets are still sent over each peer connection and a modified client could choose to unmute another track.

## Bluetooth and routing

The microphone foreground service uses Android AudioManager communication routing. It prefers a connected Bluetooth SCO/BLE communication device and falls back to the built-in speaker. Android MediaSession handles headset media-button down/up events for PTT when the headset exposes them. Media next/previous buttons cycle through the five channels when the headset sends those key events.

Headset implementations vary. Some emit reliable press/release events; others only emit a click or map a button to a media action. Toggle-to-talk fallback and configurable button mappings are not implemented yet.

## Background operation

The active session runs while the microphone foreground service is active. A persistent notification is shown with a way to return to the app. Verify behavior with screen off, locked device, Bluetooth reconnect, audio focus changes, incoming calls, and OEM battery restrictions.

## Performance validation

Measure end-to-end latency, packet loss, jitter, CPU, battery, and thermal behavior with two through six physical Android devices. Treat 150–250 ms as an initial target, not a guarantee.
