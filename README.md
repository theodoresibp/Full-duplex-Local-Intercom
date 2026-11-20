# Full-duplex Local Intercom

An Android-native, LAN-first full-duplex push-to-talk intercom for up to six participants per room.

> Status: project foundation and technical design. Audio transport, Bluetooth PTT, room discovery, and host recovery are planned implementation milestones and are not implemented yet.

## Product goals

- Android app built with Kotlin and Jetpack Compose.
- Operates over an existing Wi-Fi LAN without requiring internet access.
- Up to 6 participants per room, with multiple independent channels.
- Full-duplex audio: multiple participants may transmit simultaneously.
- Push-to-talk: a participant's microphone is transmitted only while PTT is active.
- Screen-off listening and PTT through supported Bluetooth headset media buttons.
- Any participant may create a room; host handover/election is a resilience goal.

## Initial technical direction

- Android min SDK: API 29 (Android 10).
- UI and app architecture: Jetpack Compose, MVVM, Clean Architecture.
- Audio: WebRTC peer-to-peer audio with Opus and DTLS-SRTP.
- Signaling/control: embedded Ktor HTTP/WebSocket server on the current host.
- LAN discovery: Android Network Service Discovery (NSD/mDNS), with QR/manual-IP fallback.
- Persistence: Room and DataStore.
- Background operation: microphone foreground service with persistent notification.

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md), [docs/NETWORK_PROTOCOL.md](docs/NETWORK_PROTOCOL.md), [docs/AUDIO_ENGINE.md](docs/AUDIO_ENGINE.md), and [docs/TEST_PLAN.md](docs/TEST_PLAN.md).

## Important constraints

- The Wi-Fi access point must permit client-to-client traffic; AP/client isolation must be disabled.
- LAN-only ICE host candidates are the initial connection strategy. No external STUN/TURN service is required or assumed.
- Generic Bluetooth headsets expose different media-button events. Reliable hold/release PTT depends on the headset and Android device; toggle-PTT is the compatibility fallback.
- Performance targets in the design documents are acceptance goals, not measured guarantees.

## Development status

The repository currently contains the project baseline and design documents. The next milestone is to open the project in Android Studio, sync Gradle, then implement and validate room signaling between two Android devices on the same Wi-Fi network before adding WebRTC audio.
