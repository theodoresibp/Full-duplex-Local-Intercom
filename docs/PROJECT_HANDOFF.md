# Project Handoff — Full-duplex Local Intercom

Last updated: 2026-11-21

## Purpose

Native Android LAN intercom for up to 6 participants per room. It must work over an ordinary Wi-Fi router without internet access. Any participant can create a room and become host. Audio is full duplex: multiple participants can speak simultaneously; push-to-talk gates each participant's own microphone transmission.

## Product requirements

- Android only; Kotlin, Jetpack Compose, MVVM / clean architecture.
- Minimum Android 10 (API 29).
- Up to 6 participants including host.
- LAN room discovery with Android NSD/mDNS; manual IP/port fallback.
- Embedded Ktor WebSocket server on the current host for room membership and WebRTC signaling only.
- WebRTC P2P mesh audio using Opus and DTLS-SRTP; host does not relay or mix audio.
- LAN-only ICE: no STUN/TURN dependency; only host candidates should be exchanged.
- PTT by on-screen hold control; generic Bluetooth media buttons where hardware reports usable key events.
- Audio should continue with screen off through a microphone foreground service; force-stop and permission revocation are not survivable.
- Host loss recovery is best-effort until split-brain protection and real-device tests are complete.
- Five initial channels: General, Team A, Team B, Security, Emergency.
- No recording by default.

## Repository and branch

- Repository: https://github.com/theodoresibp/Full-duplex-Local-Intercom
- Main branch is the original baseline; development is on `maintenance/clean-generated-files`.
- Draft PR: https://github.com/theodoresibp/Full-duplex-Local-Intercom/pull/1
- Do not merge to main or ask the user to pull until CI is green and review is complete.
- Latest known development commits:
  - `29f17bb23b644f41a0f34af1931a35448b43175c` — install Android 17 SDK in CI
  - `b3191dc18172c24308629662c4dd03b87167ed16` — compile against Android 17
  - `401261220ef8d7528611dabf9edad372365f55aa` — LAN ICE host-candidate filtering and prior dependency adjustments

## Current implementation snapshot

- Ktor room server/client: join, room roster, channel changes, heartbeat, signaling relay.
- NSD discovery and manual join path.
- WebRTC audio mesh: one peer connection per remote member; host only forwards signaling.
- PTT toggles local outgoing audio track.
- Foreground microphone service and MediaSession handling for headset media keys are present.
- Channel selection and room UI integration are present.
- Host recovery/election logic exists but is not proven robust.

## Latest CI status / immediate build issue

- CI at commit `401261220ef8d7528611dabf9edad372365f55aa` failed in `:app:testDebugUnitTest :app:assembleDebug`.
- Root cause in logs: transitive Compose / Lifecycle artifacts (including Compose 1.12.1) require compileSdk 37+, while app was set to compileSdk 36.
- Corrective changes have now been pushed:
  - `app/build.gradle.kts`: `compileSdk = 37`; targetSdk remains 35, minSdk remains 29.
  - `.github/workflows/android-ci.yml`: install `platforms;android-37.0` and `build-tools;37.0.0`.
- Android's official Android 17 setup guide documents compileSdk 37; this does not require raising targetSdk to 37.
- A new CI run was queued for commit `b3191dc...`; a subsequent workflow trigger may run for `29f17bb...`. Check Actions for the newest run and inspect logs before assuming success.
- If SDK package install fails, inspect exact sdkmanager package IDs. Android 17 platform packages use minor-versioned package IDs such as `android-37.0`.
- No successful unit test or APK build has yet been confirmed.

## Important review risks / next work

1. **Build first:** wait for the latest CI. Fix compile/test errors iteratively; never state green until both unit tests and debug APK assembly pass.
2. **Inspect WebRTC signaling:** SDP offer/answer ordering, ICE trickle before remote description, queued candidates, peer cleanup/reconnect, and behavior when multiple participants connect.
3. **ICE host filtering:** current matcher allows candidates containing `typ host`. Verify candidate parsing and whether local interface policy must be restricted to Wi-Fi; test IPv4 and IPv6 LAN cases.
4. **Host election / split brain:** current implementation sorts non-host members by ID and uses timed NSD discovery. This is not consensus and can produce races if partitions or delayed host heartbeats occur. Add a monotonically increasing room epoch / generation and authenticated takeover protocol before claiming robust failover.
5. **Recovery identity:** review preserved member IDs, join tokens, host control token rotation, room channel state, server restoration, and participants rejoining after host promotion.
6. **Foreground service:** verify Android 14+ microphone foreground service restrictions, notification permission behavior, Bluetooth routing, and cleanup paths.
7. **PTT hardware:** validate key-down/key-up behavior on actual generic Bluetooth headsets; many headsets expose only click/toggle events.
8. **Permissions:** audit manifest and runtime permission flow for microphone, foreground microphone service, notifications, Bluetooth connect, Wi-Fi/multicast and NSD requirements.
9. **Documentation:** keep README, ARCHITECTURE, NETWORK_PROTOCOL, AUDIO_ENGINE and TEST_PLAN aligned with implemented behavior. Distinguish design targets from verified results.
10. **Device tests required:** two or more Android phones on same router; simultaneous speech; channel isolation; screen off; lock screen; headset buttons; Wi-Fi client isolation; host disconnect and recovery. User has Poco F5 and Android Studio / wireless debugging available, but no real-device audio test has yet been done.

## Development workflow / handoff rules

- Commit and push coherent changes to `maintenance/clean-generated-files`.
- Before ending a work session or nearing context limits, update this file with current branch, commit SHA, tests, CI run IDs/results, changed files, known issues and exact next actions.
- Keep PR #1 as draft until review-ready.
- Never claim physical audio, Bluetooth, background, or failover tests have passed unless actually tested on devices.
- User does not need to pull locally until explicitly told that a tested milestone is ready.
- Codex or another AI can resume by reading this file, checking the latest branch commit and Actions run, then following the prioritized next-work list.

## Useful commands (on a developer machine)

```bash
git fetch origin
git checkout maintenance/clean-generated-files
git pull --ff-only
```

Build and unit tests:

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

On Windows:

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug
```
