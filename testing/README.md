# Testing Rocket Show

Stability is the most important property of Rocket Show: a device that stops in the middle of a show is worse than a
missing feature. Most of what can go wrong involves real hardware (audio, video, MIDI, DMX, GPIO, updates), so unit
tests alone are not enough.

This document describes how we test new builds and lists the open work. Testing happens in three layers:

| Layer | Runs on | When | Catches |
|---|---|---|---|
| [1. Unit tests and CI](#1-unit-tests-and-ci) | GitHub Actions | Every push and pull request | Logic, serialization and API regressions, broken builds |
| [2. Device self-test](#2-device-self-test) | A single unit | At the factory, first stage of every rig run, on demand | Broken audio, video, MIDI, GPIO, DMX or storage on one unit |
| [3. Hardware-in-the-loop rig](#3-hardware-in-the-loop-rig) | Real units, wired to each other | Every release candidate, soak overnight | Everything that only shows up on real hardware: analog signals, sync, updates, power loss, leaks |

None of the layers contain hardware configurations or model names. What a unit can do is reported by the unit itself
(see [Hardware features](#5-hardware-features)).

Planned layout of this folder:

```
testing/
  README.md     this file
  rig/          controller harness (TODO)
  profiles/     example rig files (TODO)
  media/        test compositions, videos and designer projects for the rig (TODO)
```

## 1. Unit tests and CI

### Today

CI (`.github/workflows/ci.yml`) runs on every push and on pull requests from forks:

- **Unit tests:** `./mvnw -B test` without the web app build.
- **Build with web app:** `./mvnw -B package -DskipTests`. The built `rocketshow.jar` is kept as a build artifact for
  14 days, so every commit has a jar to put on a test unit.

The unit tests live in `src/test/java`:

- Update state machine across reboots (`DefaultUpdateServiceTest`)
- MIDI input parsing and MIDI mapping (`MidiMessageParserTest`, `MidiMapperTest`)
- Backup and restore, broken backups (`DefaultBackupServiceTest`)
- Files of earlier releases (`CompatibilityTest`, see below)
- Lighting designer presets (`PresetMirrorTest`, `PresetIntensityTest`, `PresetStepsTest`)
- Scheduler (`DefaultSchedulerServiceTest`, `ScheduledCompositionSerializationTest`)
- MIDI timecode (`MidiTimecodeMessageFactoryTest`)
- Health service (`DefaultHealthServiceTest`) and EEPROM version parsing (`DefaultEepromServiceTest`)
- Spring context start (`RocketShowApplicationTests`)

Run them locally without the web app build:

```shell
./mvnw test -Dskip.npm -Dskip.installnodenpm
```

All 100 tests pass without any hardware attached, in about a minute.

### Files of earlier releases

`CompatibilityTest` loads every file in `src/test/resources/compat/<version>/` with the current classes:
`settings.xml`, `session.xml`, `update.xml`, `compositions/*.xml` and `sets/*.xml`. Loading is strict: an element the
current classes don't read fails the test, because its content would be lost on the next save. Elements that are
dropped on purpose are listed in the test, with the reason.

So far it only has the example compositions of 2.4.1 and 3.0.0 and the default settings and session of 3.0.0. Real
files with real content catch much more.

### TODO

- [x] GitHub Actions workflow running the unit tests on every push and pull request.
- [x] Build the web app in CI as well, so frontend build breakage is caught.
- [x] Test the update state machine in `DefaultUpdateService` with `RaucService`, `RebootService` and `HealthService`
  mocked. Cases:
  - [x] Healthy or degraded first boot in the new slot: slot is marked good, state is `FINISHED` without error.
  - [x] Unhealthy first boot in the new slot: state is `FALLING_BACK`, device reboots.
  - [x] Fell back to the original slot: state is `FINISHED` with an error, nothing is marked good.
  - [x] Booted after `FALLING_BACK`: error "Update failed. Reverted original state.".
  - [x] Interrupted during `UPDATING`: error, no endless wait.
  - [x] `rauc install` fails: error, no reboot.
  - [x] `rauc status` returns no slot: the app still starts.
  - [x] Remote version equals current version: error, nothing installed.
  - [x] Update started by an older release, which did not store the original slot: finishes in the new slot, reports
    the fallback in the original slot.
- [x] Compatibility test for files of earlier releases.
  - [ ] Add real files of every release, e.g. from backups of our own units: settings with several audio buses,
    remote devices, routings and API keys, sets, sessions, compositions with several buses, MIDI routings and video.
  - [ ] Cover designer projects and fixtures.
  - [ ] Cover the settings migrations through `DefaultSettingsService`. Today a test can't set its settings directory.
- [x] `MidiMessageParser`: messages split across reads, running status, real-time bytes inside messages and SysEx,
  SysEx without end byte.
- [x] MIDI mapping (`MidiMapper`, `ChannelMapping`), for the cases with a clear expected result (see
  [Known issues](#8-known-issues)).
- [ ] MIDI routing (`MidiRouter`).
- [ ] `CompositionPipelineBuilder`: expected pipeline for a set of compositions and audio bus settings (channel
  mapping, mix matrix). It creates real GStreamer elements, so the CI runner needs GStreamer and its plugins.
- [x] Backup and restore round trip, broken and incomplete backups (`BackupService`).
- [x] `RocketShowApplicationTests` passes without GStreamer, OLA or GPIO hardware, checked by CI.

## 2. Device self-test

A unit checks its own hardware, with nothing attached except optional loopback cables. It runs through
`POST /api/system/test`, which calls `HealthService.testSystem()`. Today that method is an empty stub
(`src/main/java/com/ascargon/rocketshow/health/DefaultHealthService.java`).

### Where it is used

- **At the factory, during provisioning.** The endpoint is reachable without login as long as the initial setup is not
  completed (`SecurityConfig.java:72`), like the device information and update endpoints. Sequence: flash the image,
  store the device information, run the self-test on a fixture with loopback cables, keep the result with the serial
  number, ship.
- **As the first stage of every rig run.** A unit that fails its self-test is not tested further, so rig failures
  point at real problems instead of a loose cable.
- **On demand by an admin**, e.g. when helping a user. It plays test tones and switches outputs, so it never runs by
  itself: not at boot, not after an update.

### What it proves and what it does not

It proves everything up to the driver: the pipelines start, the devices open, signals leave the software, MIDI and
GPIO loopbacks come back. It cannot prove that analog audio sounds right, that an HDMI picture appears or that the DMX
signal is electrically correct. That needs external measurement: the rig ([section 3](#3-hardware-in-the-loop-rig))
or a factory fixture.

It does not use the default composition or any user files: a factory unit has no user content, and a user's default
composition can be anything. All signals are generated (GStreamer `audiotestsrc` and `videotestsrc`, fixed MIDI and DMX
patterns), so the result does not depend on media files.

### Flow

1. Refuse with `409 Conflict` while a composition other than the default composition is playing. The self-test never
   interrupts a show.
2. Stop the default composition.
3. Suspend MIDI triggers, GPIO triggers and MIDI forwarding to remote devices, so test signals cannot start
   compositions or actions, here or on other units.
4. Run the checks below. A check only runs if the unit has the hardware ([section 5](#5-hardware-features)). Otherwise
   it is `SKIPPED` with the reason.
5. Restore, even if a check failed or threw: stop the test pipelines, set the test GPIO outputs low, send MIDI "all
   notes off", zero the test DMX values, resume the triggers, restart the default composition.
6. Write every check to the log (so it shows up in the downloaded logs) and return the result.

The call is synchronous and takes less than a minute.

### Checks

| Check | What it does | Passes when |
|---|---|---|
| `health` | Reads the health status | Severity is `OK` or `DEGRADED`. The reasons go into the details. |
| `storage` | Writes, reads back and deletes a 1 MB test file in the data directory | The content matches |
| `audio` | Plays a test tone on every channel of every audio output for 3 seconds, through the same sink as a composition. Measures with the pipeline's `level` element. | The pipeline reaches `PLAYING`, no error on the bus, every channel shows the tone's level (±3 dB) |
| `video` | If a display is connected: plays `videotestsrc` on the video output for 3 seconds | The pipeline reaches `PLAYING`, no error on the bus |
| `midi-out` | Sends a fixed burst of 200 messages (note on/off, program change, control change) on every MIDI out device | Sending succeeds |
| `midi-loopback` | Listens on every MIDI in device during each burst | Every message arrives once, in order, unchanged. Reports which out device arrived on which in device. |
| `gpio-out` | Switches the GPIO outputs configured in the settings high and low once per second for 5 seconds (visible on a fixture LED). On a unit without GPIO settings (factory): every user pin. Never touches reserved pins or pins configured as inputs. | The state read back matches |
| `gpio-loopback` | For each output → input pair given in the request: switches the output, reads the input. Pairs with a reserved pin are refused. | The input follows the output |
| `dmx` | Sends a ramp on every patched output port for 3 seconds, then zero | `olad` is running, the ports are patched, no error from OLA |

A loopback that receives nothing is `SKIPPED` ("no loopback cable"), unless the request expects it. Then it is
`FAILED`. This way the same test runs on a fixture with cables and on a unit without them.

### Request and result

```http
POST /api/system/test
Content-Type: application/json

{
  "expect": ["midi-loopback", "gpio-loopback"],
  "gpioLoopback": [{ "out": 22, "in": 27 }]
}
```

Both fields are optional. An empty request runs all checks the unit has hardware for and skips loopbacks that receive
nothing.

```json
{
  "result": "FAILED",
  "startedAt": "2026-10-24T15:37:06Z",
  "durationMillis": 21400,
  "softwareVersion": "3.0.0",
  "serial": "…",
  "checks": [
    { "name": "health", "result": "PASSED", "details": "DEGRADED: EEPROM version date … is older than …" },
    { "name": "audio", "target": "8 channels", "result": "PASSED", "details": "-12.1 to -12.4 dBFS" },
    { "name": "video", "result": "SKIPPED", "details": "No display connected" },
    { "name": "midi-loopback", "target": "MIDI out 1 → MIDI in 1", "result": "FAILED", "details": "196 of 200 messages received" }
  ]
}
```

`result` is `FAILED` if any check failed, otherwise `PASSED`. Skipped checks do not fail the run.

### TODO

- [ ] Result model (`SelfTestResult`, `SelfTestCheck`) returned by `POST /api/system/test`, `409` while playing.
- [ ] Suspend and resume MIDI triggers, GPIO triggers and MIDI forwarding to remote devices.
- [ ] Checks: `health`, `storage`, `audio`, `video`, `midi-out`, `midi-loopback`, `gpio-out`, `gpio-loopback`, `dmx`.
- [ ] Restore step that always runs, also after an exception.
- [ ] Unit tests for the orchestration with mocked services: skip logic, `expect`, `409`, restore after a failing
  check.
- [ ] Web app: "Run self-test" for admins in the system settings, with a warning that it plays test tones and switches
  outputs, showing the result table.
- [ ] Document the factory provisioning sequence.

## 3. Hardware-in-the-loop rig

A controller (laptop or spare Raspberry Pi) drives two or more Rocket Show units through the REST API and WebSockets
and measures what physically comes out of them. Almost everything a test needs is already exposed:

- REST (`/api/...`, authenticated with the `X-API-Key` header): transport, sets, compositions, MIDI send, settings,
  backup and restore, update, health, self-test.
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
| GPIO | A user pin → B user pin, common ground, ~1 kΩ series resistor | GPIO action in a composition on A | B's GPIO trigger starts a composition, visible in B's `/api/state` |
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
- [ ] REST and WebSocket client with API key support.
- [ ] Run the self-test ([section 2](#2-device-self-test)) on every unit as the first stage.
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

The controller holds a rig file. It only describes the rig: which units take part and which cables connect them. It
contains no hardware configuration: what a unit has comes from the unit itself ([section 5](#5-hardware-features)).

### 4.1 Rig file

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

MIDI, audio and DMX links can also be detected instead of declared: send a unique note, tone or DMX value from each
output and see where it arrives. Then the rig file shrinks to the list of units and the GPIO links. GPIO links are not
probed, because switching unknown pins on a unit is not safe.

### 4.2 Check against the units

Before a run, the harness asks every unit for its features ([section 5](#5-hardware-features)) and checks the rig file
against them. A link to a MIDI port, audio channel, DMX port or GPIO pin that the unit does not report fails the run
early with a clear message instead of a confusing timeout later. Tests that need a feature a unit does not have are
skipped, not failed.

### 4.3 Baseline settings

Before a run, the harness puts every unit into a known state with `POST /api/system/settings` or by restoring a test
backup (`POST /api/system/restore-backup`). Afterwards it restores the unit's original settings. Without this, results
depend on whatever was configured last and the tests become flaky.

The rig file is not stored in Rocket Show's own settings: test configuration must never ship to devices in the field.

### TODO

- [ ] Rig file format and loader.
- [ ] Check the rig file against the features the units report.
- [ ] Link detection for MIDI, audio and DMX.
- [ ] Baseline settings, backup of the original settings and restore after the run.
- [ ] Example rig file in `testing/profiles/`.

## 5. Hardware features

There can be several SPARK models and revisions, and the community edition runs on any Raspberry Pi with any USB
interfaces. So neither the self-test, the harness nor the tests know about models. Instead:

1. Every unit reports its own features.
2. Every test declares the features it needs, e.g. `midi-out`, `midi-in`, `gpio`, `ab-update`.
3. A test runs on a unit only when the unit has all of them. Otherwise it is skipped with the reason.

### Where the features come from

Most features can be discovered at runtime. Only a few physical facts cannot, and those come from a hardware
description on the unit itself, never from the harness.

| Feature | Source |
|---|---|
| Audio outputs and channel count | Discovered: `GET /api/audio/devices`, `GET /api/audio/max-channels` |
| MIDI in and out devices | Discovered: `GET /api/midi/in-devices`, `GET /api/midi/out-devices` |
| DMX and Art-Net output ports | Discovered: `GET /api/lighting/ola-output-ports` |
| Display connected | Discovered: `HdmiService.isConnected()` (not exposed through the API yet) |
| A/B updates with rollback | Discovered: `raucSlot` is set in `GET /api/system/health` |
| Watchdog, read-only root file system | Hardware description |
| GPIO pins free for users and tests | Hardware description |
| Reserved GPIO pins, e.g. a reset button | Hardware description |

### Hardware description

A small file per model and revision, delivered with the image or written during provisioning next to
`/provision/device-information.conf`. A new model or revision only needs a new file, no code change. Format to be
defined, for example:

```yaml
features: [watchdog, read-only-root]
gpio:
  user: [22, 23, 24, 27]
  reserved:
    - { pin: 17, use: reset-button }   # held low for 3 s = factory reset
```

A unit without a description (e.g. the community edition on a plain Raspberry Pi) reports its discovered features
only. Its GPIO pins for the rig come from the rig file.

`GET /api/system/hardware` returns the merged result: discovered features plus the description. The existing
`Capabilities` class (`src/main/java/com/ascargon/rocketshow/settings/Capabilities.java`) is not exposed through any
endpoint today and could become this.

The app itself should use the same description. Today its hardware specifics are hard-coded behind
`DeviceInformation.isAvailable()`: the reset button is always GPIO 17 (`DefaultRaspberryGpioInService.java:69-75`), and
the watchdog, the read-only file system and the A/B updates are switched on for every provisioned unit. A model with a
different reset pin would need a code change.

### Safety rules

- The self-test and the harness only switch GPIO pins listed as user pins, or declared in the rig file for units
  without a description. Reserved pins are never touched. A reset button held low for 3 seconds factory resets the
  unit.
- The rig loader refuses a rig file that wires anything to a reserved pin.
- Use a series resistor on GPIO links, in case a pin is configured as output on both sides.
- Use units that are not needed for a real show. Settings are overwritten during a run.

### TODO

- [ ] Define the hardware description format and where it lives (image or provisioning).
- [ ] `GET /api/system/hardware`: discovered features plus the description.
- [ ] Expose whether a display is connected.
- [ ] Read reserved pins (reset button) from the description instead of hard-coding GPIO 17.
- [ ] Turn the `isAvailable()` checks that are really features (watchdog, read-only root, A/B updates) into feature
  checks.
- [ ] Harness: feature tags on tests, skip with reason.
- [ ] Rig loader: refuse wiring to reserved pins.
- [ ] Document how community users submit rig files and results for their hardware.

## 6. Test scenarios per release candidate

### 6.1 Update paths

- [ ] Update every rig unit from each previous release to the candidate (`POST /api/system/update?testBranch=true`),
  not only fresh installs.
- [ ] Before updating, restore real show backups (our own shows, customer backups with permission).
- [ ] After the update, check version, RAUC slot, health, settings and compositions, and run the self-test.
- [ ] Fresh image install and first-boot setup, for every image we ship.

### 6.2 Forced update failures (units with A/B updates)

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
- [ ] Fill the unused `freeMemory` field of `HealthStatus`.
- [ ] Run once in a closed case to check thermals.

## 7. Release flow

1. CI is green on the commit.
2. The build is published to the test branch.
3. Rig units update through the test branch.
4. The rig suite runs, starting with the self-test (target: under an hour).
5. Soak overnight.
6. Promote to stable.

### TODO

- [ ] Automate steps 3-5 on the controller.
- [ ] Publish the test report with the release.

## 8. Known issues

Found by reading the code. Each one gets a unit test or rig test before it is fixed.

### Fixed

- [x] **A failed update was reported as successful.** `UpdateState.originalRaucSlot` was read after the reboot but
  never set. When the device fell back to the original slot, the update was reported as `FINISHED` without an error
  and the old slot was marked good. Now the original slot is stored and the running version is compared as well
  (`DefaultUpdateServiceTest`). Updates that start from a release before the fix can still be misreported when they
  fall back, because the old release runs that check.
- [x] **The app did not start if `rauc status` returned no slot after an update.** (`DefaultUpdateServiceTest`)
- [x] **Serial MIDI input lost or garbled messages.** Messages with running status were garbled or dropped, a
  real-time byte (e.g. MIDI clock) inside a message replaced the message, and a SysEx without its end byte swallowed
  all following input (`MidiMessageParserTest`).
- [x] **Restoring a broken backup deleted all data.** An incomplete or broken upload deleted the settings,
  compositions, sets and media, unpacked nothing and rebooted. Now the backup is checked first and the restore is
  refused with an error (`DefaultBackupServiceTest`).

### Open

- [ ] **The post-update check only proves the app starts.** `finishUpdate()` accepts `OK` and `DEGRADED`. A build
  that boots but cannot play audio is marked good. Decide which checks must pass before `mark-good`.
- [ ] **A hung synchronized unit blocks the main unit.** `RemoteDevice` only sets a connect timeout, no read timeout.
  If a remote unit accepts the connection but never answers, `DefaultPlayerService.play()` waits the full 60 seconds
  while holding the player lock, so stop and pause wait as well. Scenario 6.4 "Freeze unit B" confirms it.
- [ ] **2.x compositions lose their audio bus.** They assign audio files to a bus by name (`<outputBus>`), 3.0 only
  reads the bus UUID. All audio files of such a composition play on the first bus, e.g. a click track meant for the
  in-ear monitors on the PA. This affects restoring a 2.x backup on 3.0. Decide whether 2.x files are supported. If
  so, map the bus name to its UUID when loading.
- [ ] **Settings without a version end up at version 3, while the current version is 2.** `migrateFromOldSettings()`
  runs `migrateToVersion2()` and then `migrateToVersion3()`. Every following start rejects the settings as "saved with
  a newer version" and logs an error. They are kept anyway, because they are set before the check.
  `migrateToVersion3()` also creates the MIDI note triggers for the old MIDI controls but never adds them to the
  settings, so the old controls are lost. Only settings of very old releases are affected.
- [ ] **The global MIDI channel and note offsets seem to have no effect.** The MIDI settings page offers them, but
  routing mappings default their offsets to `0` instead of "not set", so the offsets of the parent mapping are never
  used. Routings of the live MIDI input don't use the global mapping at all. `overrideParent` is checked on the parent
  instead of the mapping itself. Decide the intended behavior, then test it in `MidiMapperTest`.
- [ ] **Creating a backup ignores the exit code of `tar`.** A failed backup (e.g. disk full) is offered for download
  as if it was complete.
