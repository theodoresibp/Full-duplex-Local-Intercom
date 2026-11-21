# Full-duplex Local Intercom

Native Android intercom for a private Wi-Fi LAN, designed for up to six participants per room. No cloud service, account, STUN, or TURN server is required for the intended same-LAN setup.

## Implemented

### Room management and discovery
- Any device can create a room and become its host.
- Embedded Ktor WebSocket server for room membership and signaling.
- Android NSD/mDNS discovery, with manual IP/port join fallback.
- Random 256-bit room session token; six participants maximum including host.
- Versioned room snapshots, member join/leave updates, and host heartbeat.
- The host opens a local signaling connection too, so it participates in the same peer mesh as guests.

### Audio and channels
- WebRTC peer-to-peer audio mesh: one direct connection per remote participant (up to five per device).
- Audio is sent directly between devices; the host does not relay or mix media.
- DTLS-SRTP protects WebRTC media. ICE is configured without STUN/TURN for same-LAN host candidates.
- Five logical channels: General, Team A, Team B, Security, and Emergency.
- Channel selection is shared through room state; remote tracks outside the selected channel are muted locally.
- Hold-to-talk gates the local microphone track. Multiple participants can talk simultaneously.
- Microphone foreground service keeps the active session alive while the screen is off.
- Android MediaSession handles supported Bluetooth headset media-button press/release events as PTT.
- Audio routing prefers a connected Bluetooth communication headset and falls back to the phone speaker.

## Not yet implemented or physically validated

- Automatic host election and recovery after the host process/device disappears.
- QR-code joining, room persistence, account identity, and configurable headset button mappings.
- Toggle-to-talk fallback for headsets that do not expose reliable press/release events.
- Physical validation on two through six Android devices, including screen-off operation, Bluetooth reconnect, latency, packet loss, battery, and thermal behavior.
- CI build/test status is not a substitute for real-device audio testing.

The current WebSocket signaling endpoint uses `ws://` on the local network; the session token is therefore not encrypted at the signaling layer. Use only a trusted Wi-Fi network and do not expose the host port to an untrusted network. WebRTC media itself is protected by DTLS-SRTP.

## Requirements

- Android minimum SDK: API 29 (Android 10).
- Kotlin, Jetpack Compose, MVVM, Ktor, coroutines, and WebRTC Android SDK.
- Router/AP must allow client-to-client traffic; disable AP/client isolation.
- Discovery requires mDNS/multicast. Manual IP join remains available if multicast is blocked.
- Microphone permission is requested before creating or joining a room.

## Build and test

Open the project in Android Studio and run the `testDebugUnitTest` and `assembleDebug` Gradle tasks. On a machine with Gradle installed:

```bash
gradle :app:testDebugUnitTest :app:assembleDebug
```

## Documentation

- [Architecture](docs/ARCHITECTURE.md)
- [Network and signaling protocol](docs/NETWORK_PROTOCOL.md)
- [Audio engine](docs/AUDIO_ENGINE.md)
- [Test plan](docs/TEST_PLAN.md)
