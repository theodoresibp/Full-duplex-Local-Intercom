# Test Plan

CI unit tests cover room rules, six-member capacity, membership removal, and channel roster updates. The following acceptance scenarios still require physical-device validation unless marked otherwise.

| ID | Scenario | Implementation status | Expected result |
|---|---|---|---|
| TC-01 | Create room | Implemented | Any participant can create a room and becomes coordinator |
| TC-02 | Discover room | Implemented; device test pending | Nearby room appears over LAN when NSD multicast is available |
| TC-03 | Fallback join | Implemented; device test pending | Manual IP/port join works when multicast discovery is blocked |
| TC-04 | Capacity | Implemented; unit tested | Sixth participant joins; seventh is rejected cleanly |
| TC-05 | Full duplex | Implemented in WebRTC mesh; device test pending | At least two participants transmit and are heard simultaneously |
| TC-06 | PTT | Implemented in UI/audio track; device test pending | Local microphone is sent only while PTT is active |
| TC-07 | Channel isolation | Implemented as receiver-side track gating; device test pending | Only the selected channel is played |
| TC-08 | Screen off | Foreground service implemented; device test pending | Existing session continues with screen locked |
| TC-09 | Headset PTT | Partially device-tested | Wired inline-button PTT works in Toggle mode on the user's test device; Press & Hold did not work. Bluetooth TWS physical-button behavior remains untested; on-screen PTT works. Click-only/key-up behavior varies by headset and needs further validation |
| TC-10 | Headset routing | Initial wired and Bluetooth TWS audio paths device-tested; reconnect test pending | Two-device communication worked with one wired headset and one Bluetooth TWS; reconnect and route-switch behavior still need validation |
| TC-11 | Network recovery | Basic host-disconnect recovery implemented; transient Wi-Fi interruption not validated | Session recovers after a supported host reconnect or reports a clear failure |
| TC-12 | Host loss | Basic deterministic handover implemented; device test pending | New coordinator is elected and room state is restored after heartbeat timeout |
| TC-13 | Security | Partial | Media is encrypted and unauthorized room/signaling targets are rejected; signaling WebSocket is still cleartext on the LAN |
| TC-14 | Six-device soak | Not validated | Stable session under sustained use; record battery, heat, latency and packet loss |

## Device/network matrix

- At least two Android OEMs and multiple Android versions.
- Wi-Fi router with client isolation disabled.
- Repeat with multicast blocked to verify manual-IP fallback.
- Bluetooth headsets from different vendors and button behaviors.
- Screen on/off, app backgrounded, Bluetooth reconnect, audio focus interruption.
- Test with two devices first, then add devices up to six.

## Metrics

Record device model, Android version, Wi-Fi band, RSSI, participant count, active speakers, median/p95 end-to-end latency, packet loss, jitter, CPU, battery drain and thermal state.


## User device-test notes — 2026-11-25

- Two Android devices successfully communicated over the local Wi-Fi room.
- Device A used a wired headset with an inline physical button. PTT worked in Toggle mode; Press & Hold did not work on this headset.
- Device B used Bluetooth TWS. PTT was operated using the in-app on-screen control; the TWS physical touch/button has not yet been tested for media-key delivery.
- This confirms a successful initial two-device audio path, but does not yet verify simultaneous full-duplex speaking, channel isolation, screen-off operation, Bluetooth physical-button mapping, reconnect, or host failover.
