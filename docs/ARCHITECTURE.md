# Architecture

## Scope

Native Android application for a private Wi-Fi LAN. A room has at most six participants, including the host, and five logical audio channels.

## Implemented components

- **Presentation:** Jetpack Compose screens and a ViewModel for room setup, discovery, channel selection, and hold-to-talk.
- **Room signaling:** Ktor CIO embedded WebSocket server on the host Android device. The host also opens a local WebSocket endpoint so it participates in the same signaling mesh as guests.
- **Discovery:** Android NSD/mDNS advertisement and browsing. Manual IP/port join is the fallback when multicast is blocked.
- **Media:** WebRTC peer-to-peer audio mesh. Each device creates one PeerConnection per other room member; the host does not relay or mix audio.
- **Background:** microphone foreground service with persistent notification; Android MediaSession receives supported Bluetooth media-button events.
- **Audio routing:** AudioManager communication mode, Bluetooth headset preference, and speaker fallback.

Room database persistence, DataStore preferences, QR join, and host election are planned but not implemented.

## Audio topology

For six participants, each device has up to five peer connections. Each participant publishes one mono audio track to peers. PTT gates the local outgoing microphone track; remote tracks are enabled only for the locally selected channel. Multiple outgoing speakers are allowed simultaneously, so this is full duplex rather than a half-duplex floor-control system.

The host manages membership, channel state, and signaling but does not mix or relay media. This avoids making the host a bandwidth bottleneck, at the cost of mesh bandwidth and connection count.

## Host lifecycle

1. Any participant may create a room and become coordinator.
2. The server broadcasts a versioned room snapshot and heartbeat every two seconds.
3. Clients mark the host unavailable after 6.5 seconds without incoming room traffic.
4. Participants are ordered by stable member ID. The first available candidate starts a new host server with a restored roster; later candidates wait for the new room advertisement before attempting promotion.
5. The new host increments no explicit room epoch yet; peers rejoin through NSD using their previous member IDs and the room session token.
6. The host also opens a private local signaling socket using a separate host-only credential.

This is a best-effort handover, not seamless failover. It depends on multicast discovery and has not yet been tested on physical devices; recovery under 10 seconds is a target, not a guarantee.

## Android lifecycle and permissions

- Request microphone permission in a user-visible flow before creating or joining a session.
- Start a microphone-type foreground service from the visible user action and maintain a persistent notification.
- MediaSession maps supported headset media-button key-down/key-up events to PTT. Headsets that expose only a click may not provide reliable hold-to-talk.
- AudioManager selects an available Bluetooth communication device when possible and otherwise prefers the built-in speaker.
- Screen-off behavior, Bluetooth reconnect behavior, incoming-call interruption, and OEM battery policies require physical-device validation.
- The app cannot guarantee survival after force-stop, permission revocation, process termination, or OEM task-killer intervention.

## Security

- WebRTC media transport uses DTLS-SRTP.
- Room join requires a random 256-bit session token.
- The local WebSocket signaling channel currently uses cleartext `ws://`; do not use it on an untrusted LAN or expose the host port to the internet.
- The server validates room membership and signaling recipient IDs. Room epochs, host-election authorization, and fine-grained per-channel signaling ACLs remain future work.
- No audio recording is implemented.

## Initial quality targets (not yet measured)

- 6 participants per room.
- 5 default channels.
- LAN discovery under 5 seconds where multicast is supported.
- Channel switching under 1 second.
- End-to-end audio latency target 150–250 ms on a healthy Wi-Fi LAN.
- Host recovery target under 10 seconds.
