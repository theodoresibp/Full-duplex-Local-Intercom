# Test Plan

All targets below are acceptance goals until validated on physical devices.

| ID | Scenario | Expected result |
|---|---|---|
| TC-01 | Create room | Any participant can create a room and becomes coordinator |
| TC-02 | Discover room | Nearby room appears over LAN when NSD multicast is available |
| TC-03 | Fallback join | QR/manual IP join works when multicast discovery is blocked |
| TC-04 | Capacity | Sixth participant joins; seventh is rejected cleanly |
| TC-05 | Full duplex | At least two participants transmit and are heard simultaneously |
| TC-06 | PTT | Local microphone is sent only while PTT is active |
| TC-07 | Channel isolation | Only the selected channel is played |
| TC-08 | Screen off | Existing session continues with screen locked |
| TC-09 | Headset PTT | Supported headset button controls PTT; unsupported behavior has a configurable fallback |
| TC-10 | Headset routing | Audio moves correctly on connect/disconnect/reconnect |
| TC-11 | Network recovery | Temporary Wi-Fi interruption recovers or reports a clear failure |
| TC-12 | Host loss | New coordinator is elected and room state is restored |
| TC-13 | Security | Media is encrypted and unauthorized room/channel events are rejected |
| TC-14 | Six-device soak | Stable session under sustained use; record battery, heat, latency and packet loss |

## Device/network matrix

- At least two Android OEMs and multiple Android versions.
- Wi-Fi router with client isolation disabled.
- Repeat with multicast blocked to verify fallback discovery.
- Bluetooth headsets from different vendors and button behaviors.
- Screen on/off, app backgrounded, Bluetooth reconnect, audio focus interruption.

## Metrics

Record device model, Android version, Wi-Fi band, RSSI, participant count, active speakers, median/p95 end-to-end latency, packet loss, jitter, CPU, battery drain and thermal state.
