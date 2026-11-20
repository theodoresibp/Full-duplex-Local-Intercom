# Network and Signaling Protocol

Milestone 1 uses WebSocket for room control on the local Wi-Fi network. WebRTC and audio transport are planned for a later milestone and are not part of this protocol implementation.

## Versioned envelope

Every WebSocket message is a UTF-8 JSON text frame with this envelope:

```json
{
  "version": 1,
  "event": "join_request",
  "requestId": "uuid",
  "payload": {}
}
```

Receivers reject unsupported protocol versions and unknown or malformed requests. Frames are limited to 8 KiB. Request IDs correlate a join request with its acceptance or rejection.

## Milestone 1 events

- `join_request`: `roomId`, `displayName`, and `sessionToken`. Manual-IP joins may send an empty room ID; the host resolves it after validating the token.
- `join_accepted`: assigned `memberId` and the current room snapshot.
- `join_rejected`: a machine-readable `reason` and user-facing `message`; reasons include `invalid_token`, `room_full`, `room_not_found`, and `room_closed`.
- `member_joined`: the newly accepted member.
- `member_left`: the member ID that left.
- `room_state`: room ID/name, host name, current members, and open/full/closed status.
- `heartbeat`: a timestamp sent by the host every five seconds while the room is running.
- `leave_room`: sent by a client before a normal disconnect.

All events use the versioned envelope. Room snapshots include the host as a member. The host enforces the six-member limit, including itself, and broadcasts an updated snapshot after joins and leaves.

## LAN discovery and joining

- Advertise and browse for the DNS-SD service type `_localintercom._tcp.`.
- TXT attributes contain room ID, room name, host display name, member count, maximum member count, and status.
- The session token is never included in discovery metadata. The host shares it from the room screen; a joining user enters it before connecting.
- The service endpoint is a WebSocket at `ws://<host-address>:<port>/room`.
- If multicast discovery is unavailable or blocked by the network, users can enter the host IP address and port manually.
- The router must permit client-to-client traffic on the local Wi-Fi network.

## Validation and security

- Display names are 2–24 characters; room names are 2–32 characters.
- Maximum six active participants, including the host.
- Session tokens are generated with 256 bits of randomness and checked by the host before accepting a join.
- A room ID supplied by discovery must match the host's room ID. Manual joins omit the ID and still require the session token.
- No audio or WebRTC signaling events are implemented in this milestone.
