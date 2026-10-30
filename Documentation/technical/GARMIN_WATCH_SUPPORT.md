# Garmin watch support (G7 Scout)

A self-contained module meant to be offered back to xDrip: everything is in `com.eveningoutpost.dexdrip.garmin`, off by default, and touches xDrip core in a few marked lines.

## What it does

The [G7 Scout](https://github.com/miketosh/garmin7-scout) Connect IQ app on a Garmin watch reads a Dexcom G7 directly over Bluetooth. This module connects it to xDrip through Garmin Connect (Connect IQ companion SDK `com.garmin.connectiq:ciq-companion-app-sdk`):

- **Watch → phone:** each reading the watch accepts arrives as a `glucose` v1 message (fields below). xDrip stores it only if the watch marked it reliable, it is 40-400 mg/dL, dated within the last 24 h and not in the future, and new for that sensor and sequence. Stored readings appear in xDrip's screens, alerts, uploads and local web service like any CGM reading (`source_info` contains "Garmin watch"). Nothing is sent back to the watch.
- **Phone → watch:** "Send G7 key to watch" sends the G7 key xDrip saved after pairing, so the watch can log in to the sensor without running the pairing itself (the Bluetooth timing of the watch link is too slow for the G7's pairing rounds).

## Message from the watch: `glucose` v1

Sent once per new reading with Connect IQ `Communications.transmit`. Integers only (Connect IQ numbers are 32-bit).

| Key | Meaning |
|---|---|
| `t`, `v` | `"glucose"`, `1` |
| `tx` | Sensor's Bluetooth name (at most 8 characters) |
| `sq` | G7 reading sequence; (`tx`, `sq`) identifies a reading |
| `mg` | Glucose, mg/dL |
| `st` | Reading time, epoch seconds (watch time at receipt minus the sensor's reported age) |
| `rt` | Watch receive time, epoch seconds |
| `tr` | Sensor trend, tenths of mg/dL per minute (optional) |
| `pr` | Sensor predicted glucose, mg/dL (optional) |
| `alg` | G7 algorithm state (6 = OK) |
| `do` | Display-only flag |
| `rel` | True only if the watch shows it as a current reading |

## Settings

Settings → Smart Watch Features → **Garmin watch (G7 Scout)**:

| Key | Default | Meaning |
|---|---|---|
| `garmin_watch_enabled` | off | Talk to the watch app; starts listening at app start and when switched on |
| `garmin_watch_collector_mode` | phone | **Collector** (owner, 2026-10-30): who connects to the G7 and on which slot. *None*: nobody collects (this phone's G7 collector stays off too). *Phone as App*: this phone on the app slot 02, as without the watch. *Watch as App*: the watch on 02; this phone's collector stays off and the watch's readings are stored. *Watch as Watch*: the watch on its own slot 03; same. *Dual Collector*: the watch on 03 and this phone on 02; the phone keeps its own readings and ignores the watch's. Keep the data source on Dexcom G7. Replaces `garmin_watch_collector`, `garmin_watch_slot` and `garmin_watch_dual_collector` (converted once: dual with slot 03 → Dual, collector on with slot 02 → Watch as App, collector on otherwise → Watch as Watch, else Phone as App) |
| `garmin_watch_fallback` | off | While the watch collects alone (Watch as App / Watch as Watch), set Phone as App again after `garmin_watch_fallback_minutes` (default 30, at least 10) without a reading; checked with xDrip's missed-reading checks |
| `garmin_watch_send_key` | action | Sends the saved G7 key after a confirmation |
| `garmin_watch_diagnostics` | screen | `GarminBridgeTestActivity`: ping, key send, listener status |

## Message compatibility

At startup the phone advertises the maximum supported version of each field it can receive in a `caps` v1 message; the watch does the same for fields it receives. The flat `f` string contains entries such as `slot.s=2|slot.ts=2`. Message wire version (`v`) and individual field version are independent. A receiver advertising v3 also supports a field requiring v2. A sender omits a newer field unless the receiver advertised that field version. Each side sends its list with `r: 1` when it starts (xDrip when it starts listening, the watch app at launch) and answers a received `r: 1` once with its own list, which carries no `r`; so both learn the other's list whichever starts first (review 2026-10-30). A peer that sends no capability message is treated as supporting established v1 fields only; the five collector modes (collector.c version 2) and the optional key.name field (version 2) are therefore not sent to that legacy peer. Optional glucose.alg, glucose.do and glucose.pr are withheld unless the phone advertises them. Add a field to the capability list only after its receiving parser/handler and tests are in place.

## Key handoff

"Send G7 key" sends `{t: "key", v: 1, tx, key: 32 hex, name}` after a confirmation. `name` is Android's name for the bonded sensor whose key it is (`BluetoothAdapter.getRemoteDevice(mac).getName()`, e.g. `DXCMz8`), left out if unknown; the watch then connects only to that name, since one G7 advertises under two names and the watch cannot see addresses. The message on screen shows `#XXXX`, the first 2 bytes of SHA-256(key), which the watch's Status screen shows too.

## Who collects

Each slot takes one client, so the **Collector** setting decides who connects and where; xDrip's own G7 collector runs only in *Phone as App* and *Dual Collector*. The watch app's menu item **Collector** cycles the same five modes. Both sides send `{t: "collector", v: 1, c: "none"|"phone"|"watch02"|"watch03"|"dual", ts}` (epoch seconds of the change) when it changes; the newer change wins and on a tie the phone's stands. The five values are field `collector.c` version 2: to a peer that has not advertised it, the value is sent as the older `watch` (Watch as App / Watch as Watch), `both` (Dual) or `phone` (None / Phone as App), and an older `watch` received keeps the current watch slot (else Watch as Watch). The mode carries the slot, so the separate `slot` message of 2026-10-29 is gone. xDrip sends its mode when it starts listening and after receiving the watch's capabilities, and answers an older or tied watch message with its own; the watch retries an undelivered change after each reading it sends and at app start. A change made on the watch shows a notification on the phone.

## Files

| File | Role |
|---|---|
| `garmin/GarminBridge.java` | SDK setup on the main thread, sending, listening, storing accepted readings |
| `garmin/GarminWatchMessages.java` | Versioned message building and parsing (pure Java, unit-tested in `GarminWatchMessagesTest`) |
| `garmin/GarminWatchPreferences.java` | Preference keys and the screen's actions |
| `garmin/GarminBridgeTestActivity.java` | Diagnostics screen |
| `res/xml/pref_advanced_settings.xml`, `res/values/strings.xml` | The settings screen |

Core hooks: one line in `utils/Preferences.java` (`GarminWatchPreferences.attach(this)`), a guarded start in `xdrip.onCreate`, `Ob1G5CollectionService.getKeksKeyForBridge()`/`getKeksKeyMacForBridge()` and its `shouldServiceRun()` check, `GarminWatchPreferences.checkFallback()` in `MissedReadingService`, the diagnostics activity in the manifest, and the Connect IQ SDK dependency in `app/build.gradle`. Based on xDrip `805ba04` (2026-03-01, Android Gradle Plugin 7.4.2), which needs Connect IQ SDK 2.2.0 (2.3.0 and 2.4.0 fail in its D8); on current upstream (Gradle 8) 2.4.0 works. Nothing else changed.

## Status

Built and unit-tested; the watch → phone and key paths are not yet verified on hardware (2026-10-26).
