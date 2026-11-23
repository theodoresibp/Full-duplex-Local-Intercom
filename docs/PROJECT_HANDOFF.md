# Project Handoff — Full-duplex Local Intercom

Last updated: 2026-11-23

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
  - `09e0ecf519f04cd8398b1cea7f9383d03dae2372` — prevent concurrent room recovery attempts with an atomic compare-and-set guard; skip stale recovery if the room is already connected or the user is leaving.
  - `5756582a737adecaea1fa55d13b03f716a7ce5f0` — send local SDP before queued ICE candidates.
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

## Latest CI status / build

- Latest confirmed green CI before the newest recovery-guard change: run `44919384680` for commit `5756582a737adecaea1fa55d13b03f716a7ce5f0`; conclusion `success`.
- That CI run covers the existing Android CI workflow. The new commit `09e0ecf519f04cd8398b1cea7f9383d03dae2372` has just been pushed; its CI result must be checked before treating the branch as green.
- App SDK configuration: `compileSdk = 37`, `targetSdk = 35`, `minSdk = 29`.
- CI installs `platforms;android-37.0` and `build-tools;37.0.0`.
- Required CI tasks: `:app:testDebugUnitTest` and `:app:assembleDebug`.
- Do not ask the user to pull until CI for the latest branch head is green and code review is complete.

## Review update — 2026-11-23

- Latest code review found a WebRTC peer-registration race: the offer coroutine could start inside `createPeer()` before `ConcurrentHashMap.computeIfAbsent` had inserted the peer. A fast remote response could then be dropped because the incoming signal worker could not find the peer. Fixed by starting the offer only after peer registration, with a per-peer atomic one-shot guard. Commit: `523845d61b83c2be7b043207fb0660436a3b7ae8`.
- Serialized server-side `CHANNEL_CHANGED` roster mutation and its room-state broadcast under `roomEventsMutex`, matching join/remove/heartbeat event ordering. Commit: `0e60596bf257fd5f67abfb153720c25c77a00104`.
- The branch head is now `0e60596bf257fd5f67abfb153720c25c77a00104`. CI run `45093289346` is in progress; do not treat the branch as green until its unit-test and APK-assembly steps succeed.
- Updated `docs/TEST_PLAN.md`: basic host-disconnect recovery exists, but recovery from transient Wi-Fi interruption remains unvalidated.
- Current remaining review priorities: strict Wi-Fi-only ICE candidate/interface handling; host-promotion rollback and split-brain limitations; Android foreground microphone service and headset routing behavior; physical-device testing. No physical audio or failover test has been run.

## Review update — 2026-11-22

- Confirmed branch head before the latest change was `5756582a737adecaea1fa55d13b03f716a7ce5f0`; CI run `44919384680` completed successfully.
- Reviewed repository recovery, foreground audio service, room server, room UI, and manifest.
- Fixed a race in `KtorRoomRepository.scheduleConnectionRecovery()`: a volatile Boolean check/set was not atomic, so simultaneous WebSocket disconnect callbacks could start multiple recovery jobs. It now uses `AtomicBoolean.compareAndSet` and resets in `finally`. It also avoids starting stale recovery if the room has already reconnected or is being left.
- New commit: `09e0ecf519f04cd8398b1cea7f9383d03dae2372`. CI for this commit is pending / must be checked.
- Confirmed current implementation still has important limitations:
  - Host election is best-effort and has no room epoch / fencing token; split-brain is possible under delayed discovery or network partition.
  - Bluetooth media-button PTT depends on device firmware delivering distinct key-down and key-up events; some headsets only send a click/toggle.
  - Android audio routing currently prefers Bluetooth, then wired/USB, then built-in speaker. Real-device microphone/earpiece routing and screen-off behavior are unverified.
  - The repository uses the active Wi-Fi transport to choose a local address, but candidate filtering and network binding still need review for strict Wi-Fi-only behavior.
- No physical multi-device audio or failover test has been run.

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
