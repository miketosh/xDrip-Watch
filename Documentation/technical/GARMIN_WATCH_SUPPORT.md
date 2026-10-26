# Garmin watch support (G7 Scout)

A self-contained module meant to be offered back to xDrip: everything is in `com.eveningoutpost.dexdrip.garmin`, off by default, and touches xDrip core in a few marked lines.

## What it does

The [G7 Scout](https://github.com/miketosh/garmin7-scout) Connect IQ app on a Garmin watch reads a Dexcom G7 directly over Bluetooth. This module connects it to xDrip through Garmin Connect (Connect IQ companion SDK `com.garmin.connectiq:ciq-companion-app-sdk`):

- **Watch → phone:** each reading the watch accepts arrives as a `glucose` v1 message ([LOCAL_DATA_EXCHANGE_ARCHITECTURE.md](LOCAL_DATA_EXCHANGE_ARCHITECTURE.md), section 6.3). xDrip stores it only if the watch marked it reliable, it is 40-400 mg/dL, dated within the last 24 h and not in the future, and new for that sensor and sequence. Stored readings appear in xDrip's screens, alerts, uploads and local web service like any CGM reading (`source_info` contains "Garmin watch"). Nothing is sent back to the watch.
- **Phone → watch:** "Send G7 key to watch" sends the G7 key xDrip saved after pairing, so the watch can log in to the sensor without running the pairing itself (the Bluetooth timing of the watch link is too slow for the G7's pairing rounds).

## Settings

Settings → Smart Watch Features → **Garmin watch (G7 Scout)**:

| Key | Default | Meaning |
|---|---|---|
| `garmin_watch_enabled` | off | Talk to the watch app; starts listening at app start and when switched on |
| `garmin_watch_collector` | off | The watch is the G7 collector: this phone's G7 collector (`Ob1G5CollectionService`) stays off and readings from the watch are stored (needs a started sensor). Keep the data source on Dexcom G7. Like Wear OS's "Force Wear Collection Service" |
| `garmin_watch_fallback` | off | While the watch collects, make the phone the collector again after `garmin_watch_fallback_minutes` (default 30, at least 10) without a reading; checked with xDrip's missed-reading checks |
| `garmin_watch_send_key` | action | Sends the saved G7 key after a confirmation |
| `garmin_watch_diagnostics` | screen | `GarminBridgeTestActivity`: ping, key send, listener status |

## Key handoff

"Send G7 key" sends `{t: "key", v: 1, tx, key: 32 hex, name}` after a confirmation. `name` is Android's name for the bonded sensor whose key it is (`BluetoothAdapter.getRemoteDevice(mac).getName()`, e.g. `DXCMz8`), left out if unknown; the watch then connects only to that name, since one G7 advertises under two names and the watch cannot see addresses. The message on screen shows `#XXXX`, the first 2 bytes of SHA-256(key), which the watch's Status screen shows too.

## Who collects

Only one client may use the sensor's app slot, so exactly one side connects to the G7. The switch above and the watch app's menu item **Collector** (Watch / Phone) both set it, and each sends `{t: "collector", v: 1, c: "watch"|"phone", ts}` (epoch seconds of the change) to the other. The newer change wins; on a tie the phone's stands. xDrip sends its choice when it starts listening (time 0 if never changed) and answers an older or tied watch message with its own; the watch retries an undelivered change after each reading it sends and, at app start, sends its own (a never-set watch sends time 0, so xDrip's answer wins). A change made on the watch shows a notification on the phone.

## Files

| File | Role |
|---|---|
| `garmin/GarminBridge.java` | SDK setup on the main thread, sending, listening, storing accepted readings |
| `garmin/GarminWatchMessages.java` | Versioned message building and parsing (pure Java, unit-tested in `GarminWatchMessagesTest`) |
| `garmin/GarminWatchPreferences.java` | Preference keys and the screen's actions |
| `garmin/GarminBridgeTestActivity.java` | Diagnostics screen |
| `res/xml/pref_advanced_settings.xml`, `res/values/strings.xml` | The settings screen |

Core hooks: one line in `utils/Preferences.java` (`GarminWatchPreferences.attach(this)`), a guarded start in `xdrip.onCreate`, and `Ob1G5CollectionService.getKeksKeyForBridge()`.

## Status

Built and unit-tested; the watch → phone and key paths are not yet verified on hardware (2026-10-26).
