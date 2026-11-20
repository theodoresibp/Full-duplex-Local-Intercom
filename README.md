# Full-duplex Local Intercom

An Android-native, LAN-first intercom project for up to six participants per room.

## Implemented: Milestone 1 — Room management and LAN discovery

- Create a room with a display name and room name. The creator runs an embedded Ktor WebSocket server and is the host.
- Advertise and discover rooms on the local network through Android NSD/mDNS.
- Join a discovered room or enter the host IP address, port, and session token manually.
- Enforce a six-member limit, including the host, and validate the room session token.
- Show room and member state, including real-time joins and departures, and leave/close a room.
- Use a versioned WebSocket control protocol. See [docs/NETWORK_PROTOCOL.md](docs/NETWORK_PROTOCOL.md).

The host must keep the app process running while hosting. Closing/leaving the room stops its server and withdraws its NSD advertisement. Background hosting and recovery after host process loss are not implemented.

## Not implemented yet

WebRTC, audio transport, push-to-talk, Bluetooth headset controls, host recovery, and persistent room storage are not part of Milestone 1.

## Requirements and network notes

- Android minimum SDK: API 29 (Android 10).
- Built with Kotlin and Jetpack Compose, using MVVM and Clean Architecture.
- Both devices need to be on a reachable local network. The access point must allow client-to-client traffic; client/AP isolation can prevent joining.
- Discovery depends on Wi-Fi multicast/mDNS. If multicast is unavailable, use manual join with the host IP, port, and session token.
- No internet service is required for local room signaling. Do not expose the host port to untrusted networks: Milestone 1 uses a room session token but does not include transport encryption or account-based identity.

## Build and test

From the repository root:

```bash
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
```

## Project documentation

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md), [docs/NETWORK_PROTOCOL.md](docs/NETWORK_PROTOCOL.md), [docs/AUDIO_ENGINE.md](docs/AUDIO_ENGINE.md), and [docs/TEST_PLAN.md](docs/TEST_PLAN.md).
