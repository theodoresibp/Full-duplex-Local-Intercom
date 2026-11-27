# Network and Signaling Protocol

The Ktor WebSocket server carries room control and WebRTC signaling only. It never carries audio. Audio media travels directly between peers over WebRTC DTLS-SRTP.

## Versioned envelope

Every WebSocket message is a UTF-8 JSON text frame:

```json
{
  "version": 1,
  "event": "join_request",
  "requestId": "uuid",
  "payload": {}
}
```

Unsupported protocol versions and malformed envelopes are rejected or ignored. Frames are limited to 8 KiB. Request IDs correlate a join request with its acceptance or rejection.

## Room events

- `join_request`: `roomId`, `displayName`, and `sessionToken`. Manual-IP joins may send an empty room ID. The host's own local signaling socket additionally sends its pre-existing host `memberId` and a separate private `hostToken` that is never shared with guests. During host handover, existing guests send their previous `memberId` to restore their roster identity.
- `join_accepted`: assigned `memberId` and the current room snapshot.
- `join_rejected`: machine-readable `reason` and user-facing `message`.
- `member_joined` / `member_left`: membership notifications.
- `room_state`: room ID/name, host name, members, each member's `channelId`, and open/full/closed status.
- `channel_changed`: authenticated socket requests a new `channelId`; the server accepts only one of the five known channels and broadcasts the updated room snapshot.
- `heartbeat`: host sends a timestamp every two seconds. Clients declare the host unavailable after 6.5 seconds without room traffic and start deterministic host election.
- `leave_room`: sent by a client before a normal disconnect.

The five channel IDs are `general`, `team_a`, `team_b`, `security`, and `emergency`.

## WebRTC signaling relay

A peer sends a `signaling` event with:

- `toMemberId`: the intended peer in the current room.
- `signalType`: `offer`, `answer`, or `ice_candidate`.
- `data`: SDP or ICE candidate fields.

The server derives the sender identity from the authenticated WebSocket membership and forwards the event only to the requested active room member, adding `fromMemberId`. It does not accept a sender ID supplied by the client. Audio is not included in these messages.

The current mesh uses one PeerConnection per remote participant. The peer with the lexicographically lower member ID initiates the offer to avoid offer glare. ICE uses host candidates only; no STUN/TURN service is configured.

## LAN discovery and joining

- DNS-SD service type: `_localintercom._tcp.`.
- TXT attributes: room ID, room name, host display name, member count, maximum member count, and status.
- Session token is never published in discovery metadata.
- WebSocket endpoint: `ws://<host-address>:<port>/room`.
- Manual IP/port join is available when multicast discovery is unavailable.
- The router must permit client-to-client traffic.

## Validation and security

- Display names: 2–24 characters; room names: 2–32 characters.
- Maximum six active participants, including the host.
- Session tokens contain 256 bits of randomness and are checked before a guest is accepted.
- Manual joins still require the session token.
- WebSocket signaling currently uses cleartext `ws://`; the session token and signaling data can be observed by an attacker with access to the LAN. Use only a trusted, WPA-protected local network and do not expose the port externally.
- WebRTC media uses DTLS-SRTP encryption.
- Host handover restores the room roster using the room session token and previous member IDs. Explicit room epochs, per-member reconnect credentials, and per-channel signaling ACLs are not implemented yet.
