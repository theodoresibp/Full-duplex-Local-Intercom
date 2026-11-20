# Architecture

## Scope

Native Android application for a private Wi-Fi LAN. A room has at most six participants (including the current coordinator/host) and multiple logical channels.

## High-level components

- **Presentation:** Jetpack Compose screens; ViewModels expose immutable UI state.
- **Domain:** room, participant, channel, PTT and connection models plus use cases.
- **Data:** Room for durable local state; DataStore for preferences; repository implementations.
- **Signaling:** Ktor embedded server hosted by the current coordinator; WebSocket messages carry room state and WebRTC negotiation only.
- **Discovery:** Android NSD/mDNS service advertisement and browsing. QR/manual IP and room code are fallback paths when multicast is blocked.
- **Media:** WebRTC peer-to-peer mesh. Each device maintains a peer connection to each other participant; media is not routed through the host.
- **Background:** microphone foreground service owns the active intercom session and persistent notification.

## Audio topology

For six participants, each device has up to five peer connections. Each participant publishes one mono audio track to peers. PTT gates the local outgoing microphone track; receivers play tracks from the currently selected channel. Multiple outgoing speakers are allowed at once, so this is full duplex rather than a half-duplex floor-control system.

The host manages membership, channel state and signaling but does not mix or relay media. This avoids making the host a bandwidth bottleneck, at the cost of mesh bandwidth and connection count.

## Host lifecycle

1. Any participant may create a room and become coordinator.
2. Coordinator emits a versioned room snapshot and periodic heartbeat (initial design: every 2 seconds).
3. Peers consider the coordinator unavailable after a missed-heartbeat threshold (initial design: 6 seconds, to be tuned).
4. Eligible peers run deterministic election using the last committed room epoch and a stable candidate ordering.
5. The elected peer starts the embedded server, increments the room epoch and publishes the recovered snapshot.
6. Peers reconnect signaling and renegotiate media as needed.

This is a recovery mechanism, not seamless zero-interruption failover. Existing direct media paths may survive briefly, but channel/membership coordination pauses during election. Target recovery under 10 seconds is a test goal.

## Android lifecycle and permissions

- Request microphone permission in a user-visible flow before starting an intercom session.
- Start a microphone-type foreground service while the app is visible and user-initiated; maintain a persistent notification.
- Keep screen-off audio operation within Android foreground-service and background execution rules.
- Avoid permanent wake locks. Use Wi-Fi locks only if device testing demonstrates a need.
- Do not promise survival after force-stop, permission revocation, or OEM task-killer intervention.

## Security

- WebRTC DTLS-SRTP protects media transport.
- Room join uses a random room/session credential; never trust a room code alone as authorization.
- Validate sender identity, room epoch, membership and channel access for every control event.
- Rate-limit signaling and expire stale sessions.
- No audio recording by default.

## Initial quality targets (not yet measured)

- 6 participants per room.
- 5 default channels.
- LAN discovery under 5 seconds where multicast is supported.
- Channel switching under 1 second.
- End-to-end audio latency target 150–250 ms on a healthy Wi-Fi LAN.
- Host recovery target under 10 seconds.
