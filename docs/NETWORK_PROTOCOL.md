# Network and Signaling Protocol

WebSocket is used for signaling and room control only. Audio media flows directly between peers over WebRTC.

## Envelope

Every message uses a versioned JSON envelope:

```json
{
  "version": 1,
  "event": "room.join",
  "requestId": "uuid",
  "roomId": "room-uuid",
  "senderId": "participant-uuid",
  "timestamp": 0,
  "roomEpoch": 1,
  "payload": {}
}
```

Timestamps are Unix milliseconds. Receivers reject messages for stale room epochs and validate sender membership before applying state changes.

## Event names

- Room: `room.create`, `room.join`, `room.leave`, `room.state`, `room.host.changed`, `room.heartbeat`
- Participants: `user.joined`, `user.left`
- Channels: `channel.switch`, `channel.updated`
- PTT indicators: `ptt.start`, `ptt.stop`
- WebRTC signaling: `webrtc.offer`, `webrtc.answer`, `webrtc.ice`
- Connectivity: `connection.lost`, `connection.restored`

PTT events are for UI/presence indicators. They are not the audio transport and must not be used as the sole mechanism for gating microphone audio.

## Discovery and connection

- Advertise a versioned NSD service on the LAN (service type to be finalized during implementation).
- Browse for compatible rooms and resolve the host address/port.
- Provide QR and manual-IP fallback.
- Initial ICE configuration uses host candidates only; do not configure public STUN/TURN by default.
- The router must permit peer-to-peer client traffic.

## Limits and validation

- Maximum six active participants.
- Maximum message size and allowed event names are enforced.
- Authenticate room joins with a random session token; do not expose the token in discovery broadcasts.
- Validate channel membership before accepting channel-scoped events.
- Use request IDs for idempotent request/response handling.
