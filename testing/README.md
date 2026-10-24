# Testing Rocket Show

Stability is the most important property of Rocket Show: a device that stops in the middle of a show is worse than a
missing feature. Most of what can go wrong involves real hardware (audio, video, MIDI, DMX, GPIO, updates), so unit
tests alone are not enough.

This document describes how we test new builds and lists the open work. Testing happens in three layers:

| Layer | Runs on | When | Catches |
|---|---|---|---|
| [1. Unit tests and CI](#1-unit-tests-and-ci) | GitHub-hosted runner | Every push and pull request | Logic, serialization and API regressions, broken builds |
| [2. Device self-test](#2-device-self-test) | A single device | On demand, in the rig, at the factory | Broken playback, GPIO or MIDI on one unit |
| [3. Hardware-in-the-loop rig](#3-hardware-in-the-loop-rig) | Real units, wired to each other | Every release candidate, soak overnight | Everything that only shows up on real hardware: sync, updates, power loss, leaks |

The harness, the tests, example rig profiles and test media live in this public repository. See
[Security](#8-security-and-what-stays-private) for what must stay private.

Planned layout of this folder:

```
testing/
  README.md     this file
  rig/          controller harness (TODO)
  profiles/     example rig profiles, e.g. spark.yaml, community-pi-usb.yaml (TODO)
  media/        test compositions, videos and designer projects (TODO)
```

## 1. Unit tests and CI

### Today

The unit tests live in `src/test/java` and cover pure logic:

- Lighting designer presets (`PresetMirrorTest`, `PresetIntensityTest`, `PresetStepsTest`)
- Scheduler (`DefaultSchedulerServiceTest`, `ScheduledCompositionSerializationTest`)
- MIDI timecode (`MidiTimecodeMessageFactoryTest`)
- Health service (`DefaultHealthServiceTest`) and EEPROM version parsing (`DefaultEepromServiceTest`)
- Spring context start (`RocketShowApplicationTests`)

Run them without the web app build:

```shell
./mvnw test -Dskip.npm -Dskip.installnodenpm
```

All 61 tests pass without any hardware attached, in about a minute. There is no CI yet.

### TODO

- [ ] Add a GitHub Actions workflow that runs `./mvnw -B test` on every push and pull request to `develop` and
  `master`. Use GitHub-hosted runners only (see [Security](#8-security-and-what-stays-private)).
- [ ] Build the web app in CI as well (`npm run prod` in `src/main/webapp`), so frontend build breakage is caught.
- [ ] Test the update state machine in `DefaultUpdateService` with `RaucService`, `RebootService` and `HealthService`
  mocked. A bug here can leave units in the field stuck or report a failed update as successful. Cases:
  - [ ] Healthy first boot in the new slot: slot is marked good, state is `FINISHED` without error.
  - [ ] Unhealthy first boot in the new slot: state is `FALLING_BACK`, device reboots.
  - [ ] Watchdog fell back to the original slot: state is `FINISHED` with an error, nothing is marked good
    (currently broken, see [Known issues](#9-known-issues-to-confirm)).
  - [ ] Booted after `FALLING_BACK`: error "Update failed. Reverted original state.".
  - [ ] Interrupted during `UPDATING`: error, no endless wait.
  - [ ] `rauc install` fails: error, no reboot.
  - [ ] `rauc status` returns no slot: the app still starts.
  - [ ] Remote version equals current version: error, nothing installed.
- [ ] Old-file compatibility: keep the files written by each released version in `src/test/resources/compat/<version>/`
  (`settings.xml`, `session.xml`, `update.xml`, compositions, sets, designer projects) and test that the current build
  loads all of them. Every update depends on this.
- [ ] `MidiMessageParser`: serial byte streams split across reads, running status, realtime bytes between messages,
  SysEx.
- [ ] MIDI routing and mapping (`MidiRouter`, `MidiMapper`, `ChannelMapping`).
- [ ] `CompositionPipelineBuilder`: expected pipeline for a set of compositions and audio bus settings (channel
  mapping, mix matrix).
- [ ] Backup and restore round trip (`BackupService`).
- [ ] Make sure `RocketShowApplicationTests` keeps passing without GStreamer, OLA or GPIO hardware.

## 2. Device self-test

`POST /api/system/test` calls `HealthService.testSystem()`, which is still an empty stub
(`src/main/java/com/ascargon/rocketshow/health/DefaultHealthService.java`). `GET /api/system/health` already reports
disk space, temperature, OLA state, recent errors, versions and the RAUC slot.

### TODO

- [ ] Implement `testSystem()` and return a result per check instead of `void`:
  - [ ] Play the default composition and watch for pipeline errors.
  - [ ] Toggle the configured GPIO outputs (e.g. every 3 seconds).
  - [ ] Send MIDI messages and expect the same messages back (needs a MIDI out → in cable).
- [ ] Do not run the self-test automatically after an update in the field: it makes noise on stage. It is meant for
  the rig and for the factory.
- [ ] Reuse the self-test as end-of-line test for new SPARK units before they are provisioned and shipped.
- [ ] Fill the unused `freeMemory` field of `HealthStatus` (needed for the soak test).

## 3. Hardware-in-the-loop rig

A controller (laptop or spare Raspberry Pi) drives two or more Rocket Show units through the REST API and WebSockets
and measures what physically comes out of them. Almost everything a test needs is already exposed:

- REST (`/api/...`, authenticated with the `X-API-Key` header): transport, sets, compositions, MIDI send, settings,
  backup and restore, update, health, device information.
- WebSockets: `/api/state`, `/api/activity/midi`, `/api/activity/audio`, `/api/activity/lighting`,
  `/api/update/state`.

```
                 ┌──────────── Controller (laptop or spare Pi) ────────────┐
                 │  test harness · USB audio in · HDMI capture · olad      │
                 └──┬───────────────┬──────────────┬──────────────┬────────┘
             REST + WebSockets   audio L        audio R         HDMI
                    │               │              │              │
           ┌────────┴──────┐  MIDI A→B, B→A  ┌─────┴─────────┐    │
           │ Unit A (main) ├────────────────►│ Unit B (sync) │    │
           │               │◄────────────────┤               │    │
           │               │ GPIO out → in   │               │    │
           └───────┬───────┴─────────────────┴───────┬───────┘    │
              smart plug                        smart plug        │
                   └────────── HDMI of unit A ───────────────────-┘
```

| Link | Wiring | Driven by | Checked by |
|---|---|---|---|
| MIDI | A out → B in, B out → A in (DIN or USB) | `POST /api/midi/send-message`, MIDI actions in compositions | `/api/activity/midi` on the other unit, MIDI triggered actions |
| GPIO | A output pin → B input pin, common ground, ~1 kΩ series resistor | GPIO action in a composition on A | B's GPIO trigger starts a composition, visible in B's `/api/state` |
| Audio | A → left, B → right input of a USB audio interface on the controller | Test composition with a different sine tone per channel and clicks at known times | Signal present, channel routing, dropouts, A↔B sync offset in ms (cross-correlation) |
| Video | HDMI → USB HDMI capture stick | Test video with a frame counter and a flash plus beep | Frames are not black, audio/video offset |
| DMX | Art-Net or sACN to `olad` on the controller, or a USB DMX interface that can receive | Lighting actions and designer projects | DMX values at known times (`olad` HTTP API, e.g. `http://controller:9090/get_dmx?u=1`) |
| Power | One smart plug per unit with a local HTTP API (Shelly, Tasmota) | Harness | Power cuts at chosen or random moments |
| Network | Main unit A synchronizes unit B (remote device with "synchronize") | Transport on A | B follows A, see audio sync offset |

### TODO

- [ ] Set up the rig: controller, 2-3 units, USB audio interface, HDMI capture stick, smart plugs, MIDI and GPIO
  cables.
- [ ] Harness in `testing/rig/`. Proposed: Python with pytest (numpy for the audio analysis, WebSocket client, runs
  on a laptop or a Pi).
- [ ] REST and WebSocket client with API key support (keys from environment variables, never in files).
- [ ] Test media in `testing/media/`: sine per channel, click track, flash plus beep video, DMX designer project with
  known values.
- [ ] MIDI loopback tests (both directions, note on, program change, MIDI triggered actions, MIDI timecode).
- [ ] GPIO loopback tests (output action on A, input trigger on B).
- [ ] Audio capture and analysis: presence, routing, dropouts, A↔B offset. Track the offset per build.
- [ ] Video capture and analysis: non-black frames, audio/video offset.
- [ ] DMX capture through `olad` on the controller.
- [ ] Smart plug control for power cut tests.
- [ ] Test report per run (pass/fail per test, sync offsets, soak graphs), stored per build.

## 4. Rig configuration

The tests are not hard-coded to one hardware. The controller holds the configuration, which has three parts.

### 4.1 Wiring profile (declared)

No API can tell which cable goes where, so the wiring is declared in a profile file. The repository contains example
profiles in `testing/profiles/`. The real profile with IPs and power plugs lives only on the controller.

```yaml
units:
  a: { host: 192.168.1.50, api_key_env: RS_A_KEY, power: shelly://192.168.1.60 }
  b: { host: 192.168.1.51, api_key_env: RS_B_KEY, power: shelly://192.168.1.61 }
capture:
  audio: { device: "USB Audio", channels: 2 }
  video: { device: /dev/video0 }
links:
  - midi:  a.out -> b.in
  - midi:  b.out -> a.in
  - gpio:  a.22 -> b.27
  - audio: a.out[1,2] -> capture.in[1]
  - audio: b.out[1,2] -> capture.in[2]
  - video: a.hdmi -> capture.video
  - dmx:   a.artnet[1] -> controller.olad
```

### 4.2 Capabilities (discovered)

Before a run, the harness compares the profile with what each unit reports and fails early with a clear message
instead of a confusing timeout:

- `GET /api/system/device-information`: SPARK or community (`available`), model, hardware revision
- `GET /api/midi/in-devices`, `GET /api/midi/out-devices`
- `GET /api/audio/devices`, `GET /api/audio/max-channels`
- `GET /api/lighting/ola-output-ports`
- `GET /api/system/current-version`, `GET /api/system/health`

Tests that need hardware a unit does not have are skipped, not failed.

### 4.3 Baseline settings (pushed)

Before a run, the harness puts every unit into a known state with `POST /api/system/settings` or by restoring a test
backup (`POST /api/system/restore-backup`). Afterwards it restores the unit's original settings. Without this, results
depend on whatever was configured last and the tests become flaky.

The wiring is not stored in Rocket Show's own settings: test configuration must never ship to devices in the field.

### TODO

- [ ] Profile format and loader.
- [ ] Capability check against the profile.
- [ ] Baseline settings per profile, backup of the original settings and restore after the run.
- [ ] Example profiles: `spark.yaml`, `community-pi-usb.yaml`.
- [ ] Safety checks in the loader (see [Hardware profiles](#5-hardware-profiles)).

## 5. Hardware profiles

SPARK and the community edition run the same show engine. The differences are in this repository, mostly behind
`DeviceInformation.isAvailable()`:

| | SPARK | Community edition |
|---|---|---|
| Updates | RAUC A/B slots with rollback (`DefaultUpdateService`, `DefaultRaucService`) | Image or JAR update |
| Watchdog | systemd watchdog (`DefaultWatchdogService`) | Not active |
| File system | Read-only root, writable data | Writable |
| Reset button | GPIO 17, held 3 s → factory reset and reboot (`DefaultRaspberryGpioInService`) | None |
| DMX | Onboard DMX512 and Art-Net | Art-Net or USB interface via OLA |
| Device information | Serial, model, revision, country in `/provision/device-information.conf` | None |

Update, rollback and watchdog tests therefore only run on SPARK units.

Community users can write a profile for their own Raspberry Pi and USB interfaces, run the suite and report the
results. That covers hardware we will never own.

### Safety rules

- **Never wire a test output to GPIO 17 on a SPARK.** Holding it low for 3 seconds factory resets the unit. The profile
  loader must refuse such a profile.
- Use a series resistor on GPIO links, in case a pin is configured as output on both sides.
- Use units that are not needed for a real show. Settings are overwritten during a run.

### TODO

- [ ] Loader rule: refuse GPIO 17 as test input on SPARK.
- [ ] Document how community users submit profiles and results.

## 6. Test scenarios per release candidate

### 6.1 Update paths

- [ ] Update every rig unit from each previous release to the candidate (`POST /api/system/update?testBranch=true`),
  not only fresh installs.
- [ ] Before updating, restore real show backups (our own shows, customer backups with permission).
- [ ] After the update, check version, RAUC slot, health, settings and compositions.
- [ ] Fresh image install and first-boot setup (community image and SPARK).

### 6.2 Forced update failures (SPARK)

These are the paths that brick units in the field, and nobody runs them by hand.

- [ ] Install a deliberately broken bundle (app exits at start): the unit must fall back to the old slot and report
  the failure.
- [ ] Cut power during `rauc install`.
- [ ] Cut power during the first boot of the new slot.
- [ ] Unhealthy first boot (e.g. health reports `FAIL_REBOOT_DEVICE`): falls back.

### 6.3 Show scenarios

- [ ] Play a full set end to end, check audio, video, DMX and MIDI against the expected timing.
- [ ] Spam play, stop, next and previous.
- [ ] Seek while playing and while paused.
- [ ] Parallel playback with `play-as-sample`.
- [ ] Default composition starts after stop.
- [ ] Scheduler starts a composition at the planned time.
- [ ] Settings save and reload while idle.
- [ ] Backup and restore.

### 6.4 Chaos

- [ ] Unplug and replug USB MIDI and USB DMX during a show.
- [ ] Reboot unit B during a show on unit A.
- [ ] Freeze unit B (`kill -STOP` the Java process), then press play on unit A.
- [ ] Pull the network cable between the units during a show.
- [ ] Cut power during a show and check the unit comes back to a usable state.

### 6.5 Soak

- [ ] Loop a set for 24-72 hours.
- [ ] Sample health, Java memory, open file handles, thread count and temperature. Leaks show up as a trend, not as a
  failure.
- [ ] Run once in a closed case to check thermals.

## 7. Release flow

1. CI is green on the commit.
2. The build is published to the test branch.
3. Rig units update through the test branch.
4. The rig suite runs (target: under an hour).
5. Soak overnight.
6. Promote to stable.

### TODO

- [ ] Automate steps 3-5 on the controller.
- [ ] Publish the test report with the release.

## 8. Security and what stays private

Public in this repository:

- The harness, the tests, test media and example profiles.

Private, never in this repository:

- RAUC signing keys.
- Update server credentials and publishing to the test branch.
- The real rig profile with IPs, and all API keys (environment variables only).
- The factory provisioning flow.

Do not connect a self-hosted GitHub runner on the home network to this public repository: a pull request from a fork
could run code inside that network. Start rig runs by hand, or let the controller watch the test branch and run the
suite itself.

## 9. Known issues to confirm

Found by reading the code. Not yet confirmed on hardware. Each one gets a unit test or rig test before it is fixed.

- [ ] **A failed update is reported as successful.** `UpdateState.originalRaucSlot` is read in the
  `DefaultUpdateService` constructor but never set. When the watchdog falls back to the original slot, the code takes
  the "booted into the new slot" branch, marks the old slot good and reports `FINISHED` without an error, while the
  device still runs the old version. Fix: set the original slot in `update()` before installing the bundle.
- [ ] **The app does not start if `rauc status` returns no slot after an update.** `getCurrentSlot()` returns `null`
  on failure, and the `DefaultUpdateService` constructor calls `.equals()` on it.
- [ ] **The post-update check only proves the app starts.** `finishUpdate()` accepts `OK` and `DEGRADED`. A build
  that boots but cannot play audio is marked good. Decide which checks must pass before `mark-good`.
- [ ] **A hung synchronized unit blocks the main unit.** `RemoteDevice` only sets a connect timeout, no read timeout.
  If a remote unit accepts the connection but never answers, `DefaultPlayerService.play()` waits the full 60 seconds
  while holding the player lock, so stop and pause wait as well. Scenario 6.4 "Freeze unit B" confirms it.
