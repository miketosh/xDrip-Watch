# Local data exchange architecture: Tandem, xDrip-Watch, and Garmin G7 Scout

**Canonical cross-repository design contract**  
**Updated:** 2026-10-26 (implementation reconciliation)  
**Phone-side repository:** [`miketosh/xDrip-Watch`, branch `garmin-bridge`](https://github.com/miketosh/xDrip-Watch/tree/garmin-bridge)  
**Watch-side repository:** [`miketosh/garmin7-scout`, branch `main`](https://github.com/miketosh/garmin7-scout/tree/main)

Read this before changing the Tandem provider, Garmin phone/watch messaging, Dexcom slot policy, or shared status/history fields. This file is the source of truth for intended end-state flows and current implementation status. The `AGENTS.md` in both repositories points here.

## 1. Owner's intended system

The goal is local pump-data and watch-data exchange. **A Nightscout server upload is not required.**

```text
                        Dexcom G7
                      ^             ^
       pump/AID channel |             | direct BLE from Garmin watch
                      |             |
                Tandem t:slim X2   Garmin Forerunner 255
                    ^     |             ^       |
                    |     | status      |       | glucose readings
          local BLE |     v             |       v
                    +-- Android xDrip-Watch / xDrip+
                                ^
                                |
                Garmin Connect IQ phone/watch message path
             xDrip -> watch: pump data; watch -> xDrip: glucose
```

This is the intended topology, not proof that each link is complete. Preserve the pump's direct relationship with the Dexcom G7. The exact ability to maintain pump status/history access alongside Tandem's official phone app, and the pump↔G7 connection, must be evaluated without disrupting therapy or CGM service.

**Pump connection ownership (2026-10-25).** *Source-derived:* PumpX2's README says to force-quit or log out of the official Tandem app to avoid conflicts, and that "after pairing with pumpX2, you will need to log out and re-pair with the official Tandem app", so the X2 appears to serve one phone app at a time (*to verify on the pump*). *Owner decision:* Tandem's app stays the daily pump connection; xDrip pairs only for short, owner-supervised test sessions, then the owner re-pairs Tandem's app. Until this changes, the Tandem → xDrip and xDrip → Garmin pump links are test-session features, not continuous ones, and the watch must treat missing pump data as normal. *Source-derived, later the same day:* PumpX2 v1.9.3 can also share the Tandem app's own connection on the same phone and only listen to the pump's replies, sending nothing and needing no pairing; `TandemX2PumpClient` defaults to that mode. If a supervised session shows it works, continuous pump data with Tandem's app kept would be possible; that would be a new owner decision. See [status, section 10](Tandem_X2_Integration_Status.md#10-connection-modes-safe-pairing-and-test-screen-2026-10-25).

## 2. Required data flows and status

| Link | Desired behavior | Current observed state |
|---|---|---|
| Tandem t:slim X2 ↔ Dexcom G7 | Pump keeps receiving G7 glucose using its pump/AID channel. Do not re-pair or displace it as a test shortcut. | Owner's intended active setup; this project must preserve it. |
| Tandem → xDrip, current status | Read pump-reported IOB and well-defined basal/profile status locally; preserve units, source timestamp, receive timestamp, and freshness. | PumpX2 adapter prototype and custom non-logging pairing override exist. It refuses to start if any Timber tree is planted; clearing stored pairing credentials is not implemented. Real-pump values, pairing, and connection coexistence/recovery remain unverified. |
| Tandem → xDrip, history | Pull supported event history locally: boluses and delivery state, basal/rate events, profile changes, suspend/resume, and any other verified event types. | Not implemented in the prototype; PumpX2 reports history support as partial. Validate field-by-field. |
| xDrip → Garmin | Send pump status and recent bolus information, with provenance/time and explicit stale/unknown/disconnected states. | `pump_status` message builder and test-screen send action exist; the watch-side receiver/display is not implemented. The bridge also sends `ping`, saved G7 `key` (including sensor name when known), and collector ownership messages. |
| Garmin → xDrip | Report accepted glucose readings acquired by the watch's **direct G7 BLE collector**, including glucose/unit, sensor/source timestamp where available, watch receive time, and suitable deduplication identity. | Watch sender and phone receiver/parser are built; host tests cover parsing/deduplication. Not verified end-to-end on physical hardware. |
| Garmin display | Keep direct-G7 glucose as the watch's CGM source, independent of the phone data relay; show phone-supplied pump values separately. | Direct-G7 collector is under development; pump-data display is not implemented. |
| xDrip local records | Optionally retain received watch readings and acquired pump history with provenance and deduplication, without creating echo loops. | Future work; record ownership/deduplication policy first. |

## 3. G7 slot policy — preferred watch slot, acceptable app-slot fallback

The owner decision is explicit:

1. **Preferred:** the Garmin requests the G7 smartwatch role, DisplayType/slot `0x03`, if the protocol/authentication path can be made to work.
2. **Acceptable end state:** if the watch slot cannot be made reliable, the Garmin may use the mobile-app role/slot `0x02`. That is an acceptable product outcome; do not block the whole integration solely because `0x03` remains unavailable.
3. **Pump slot preservation:** the t:slim X2 continues using its own pump/AID role/slot. Do not alter or reclaim that link.
4. **Single occupancy:** the app/phone slot `0x02` is not to be shared by two simultaneous G7 clients. In a final configuration using `0x02`, xDrip's own G7 collector must be stopped/not connected so the watch can own that slot. This is compatible with the target architecture because the watch becomes the CGM collector; xDrip receives readings from the watch instead.
5. **Development caveat:** during current bring-up xDrip may still collect from the phone. Do not assume a successful simultaneous phone+watch slot-`0x02` setup. Any handoff test that releases xDrip's G7 connection must be controlled, explicit, and reversible.
6. Current watch code reportedly probes `0x03` and falls back to `0x02` after failure; simulator behavior is not proof of live G7 slot acceptance. Keep source facts, simulated behavior, and physical-watch evidence separately labelled.

The numeric labels are based on community implementation documentation/research; they are not a Dexcom-issued public protocol contract. See [G7 protocol research](https://github.com/miketosh/garmin7-scout/blob/main/docs/PROTOCOL_RESEARCH.md) and [end-state architecture](https://github.com/miketosh/garmin7-scout/blob/main/docs/END_STATE_ARCHITECTURE.md).

## 4. Agent ownership and coordination

The owner is working with separate coding-agent sessions. Use this file as the durable shared handoff; decisions must not live only in a chat transcript.

- **Phone-to-pump (xDrip-Watch):** owns PumpX2 dependency/API, local BLE/authentication feasibility, strict read-only request allowlist, pump response parsing, canonical phone-side pump status/history, and Android unit tests. Do not modify direct G7 watch collection as part of this work.
- **Watch-to-Dexcom (garmin7-scout):** owns G7 discovery, authentication, slot policy implementation, direct glucose decoding, reconnect/cadence/runtime behavior, and watch-side tests. Do not make direct glucose acquisition depend on the phone relay.
- **Cross-repository contract:** xDrip-Watch maintains this canonical document; garmin7-scout mirrors a brief scope summary and links here. A change to message fields, slot policy, units, timestamps, provenance, state, or deduplication must update both sides in the same iteration.
- **Documentation/integration coordination:** the person handling docs keeps current implementation state distinct from desired behavior and points both coding agents here before interface changes.

## 5. “Nightscout protocol” versus local app/watch communication

Nightscout's API is a client/server API for a Nightscout server. It is **not** a peer-to-peer protocol that xDrip and Garmin use to talk to each other. The required feature does not need a Nightscout cloud upload.

Repository inspection found these existing xDrip mechanisms:

- **Garmin bridge:** Android uses Garmin's Connect IQ Mobile SDK and the Garmin Connect environment; the watch uses Connect IQ's Communications phone-message API. Use this as the xDrip↔Garmin transport and keep messages explicitly versioned. This transport's delivery constraints still apply; “no Nightscout cloud” does not claim Garmin Connect messaging works without its companion infrastructure.
- **Android Wear OS Data Layer:** `WatchUpdaterService` and `SendToDataLayerThread` use Google's Wearable Data Layer, with paths such as `/nightscout_watch_data`, `/xdrip_plus_syncwearbgs2`, and `/xdrip_plus_syncweartreatments`. This is a distinct Android Wear OS transport. Reuse the design ideas—bounded messages, clear paths/types, timestamps—not its APIs or package dependencies for Garmin.
- **Legacy external-status broadcast:** `ExternalStatusBroadcastReceiver` / `ExternalStatusService` accepts a short status-line intent and extracts basal/TBR or IOB-like text. It is too ambiguous for the canonical rich pump data contract; do not make it the new API.
- **Nightscout HTTP upload/download:** `NightscoutUploader` and `NightscoutTreatments` communicate with a configured Nightscout server. They are not the phone↔watch transport and are not required for this goal.

## 6. Phone/watch message types (v1 fields proposed; not frozen)

Keep the current message types separate; never overload saved G7 authentication-key transfer as a pump-data container.

| Direction | Type | Status | Purpose |
|---|---|---|---|
| Phone → watch | `ping` | Existing test | Confirms a basic phone-to-watch message. |
| Phone → watch | `key` | Existing prototype; secret | Transfers the saved Dexcom G7 key for direct watch authentication, plus the bonded sensor's advertised name when known. The key itself must never be logged or placed in pump-data messages. |
| Phone → watch | `pump_status` v1 | Phone builder + test button exist; watch receiver/display not implemented | Compact pump snapshot. Current Java builder emits `t`, `v`, `sent`, `seq`, and `link`; when snapshot values exist it may add `iob`/`iob_t` and `cb_st`/`cb_t`, with `cb_u`/`cb_pt` for non-`none` bolus states. Reserved basal/last-bolus fields are not emitted. Phone builder does not itself implement the watch freshness/display policy. |
| Watch → phone | `glucose` v1 | Watch sender + phone parser/receiver built; not verified on hardware | A new accepted reading obtained from the watch's direct G7 collector. Renamed from `glucose_reading`: the watch logs a message type only if it is ≤ 12 characters. |
| Phone ↔ watch | `collector` v1 | Implemented on both sides; host/simulator evidence, physical handoff unverified | Chooses collection ownership (`watch`, `phone`, or `both`). `both` is dual-collector mode: phone on slot `0x02`, watch on slot `0x03`; it is permitted only with slot `03`. Timestamp synchronization resolves changes. This is collection ownership, not a therapy command. |
| Phone → watch | `slot` v1 | Phone builder and watch handler implemented; physical slot acceptance unverified | Carries the requested G7 slot `s` = `auto`, `02`, or `03`. `auto` follows the watch's slot policy; `02` or `03` force the requested slot. Slot preference is separate from collector ownership. |
| Phone → watch | bounded `pump_history` batch (later) | Planned | Validated, timestamped events with deterministic IDs, explicit event semantics and strict count/size limits. |

### 6.1 Rules for every v1 message (reviewed 2026-10-25; keep both repositories aligned)

- A flat dictionary with string keys. `t` (type) and `v` (integer version, `1`) are always present. A receiver ignores a message whose `t`/`v` it does not know and logs only type and version.
- **Integers only** for numbers: insulin in milliunits, glucose in mg/dL, times in Unix epoch **seconds** (fits Connect IQ's 32-bit `Number` until 2038). Strings ≤ 12 characters. No floats, no nested containers in v1.
- **Unknown means absent.** A value that is not known is left out; `0` always means a reported zero; `null` is never sent. A field of the wrong type is treated as absent; the rest of the message stays usable unless an identity or time field it depends on is broken.
- **Every value carries its own time** (`*_t` = when the phone or watch received it from the pump or sensor; `*_pt`/`st` = the device's own time where it reports one). Receivers judge freshness from these, not from when the message arrived.
- Receivers ignore unknown keys, so later versions can add fields. Changing a field's meaning or units requires a new `v`.
- Keys starting `x_` are reserved for xDrip-modelled (not device-reported) values; v1 defines none.
- No secrets, no raw BLE/protocol bytes. Logs on both sides record type, version, field count and accept/reject reason, never values.
- No therapy-changing commands in this data contract: the watch never asks the phone or pump to deliver a bolus, change basal/profile, suspend, or resume therapy. The `collector` message only synchronizes which device connects to the G7.

### 6.2 `pump_status` v1 (phone → watch)

Sent when a new pump snapshot arrives (and on the test screen's button). Small: under 300 bytes.

| Key | Type | Meaning |
|---|---|---|
| `t`, `v` | string, int | `"pump_status"`, `1` |
| `sent` | int | Phone time of sending (epoch s) |
| `seq` | int | Phone counter; with `sent`, lets the watch drop out-of-order copies (keep the newest `sent`) |
| `link` | string | Phone's pump link: `"up"` (connected), `"down"` (was connected, now lost), `"off"` (not running). Pump data is usually absent and `off` (owner decision: test sessions only), which the watch shows as normal, not as an error |
| `iob`, `iob_t` | int, int | Pump-reported insulin on board in milliunits (Control-IQ value when the pump selects one, else the standard IOB), and when the phone received it. The pump reports no source time for IOB |
| `cb_st` | string | Current bolus: `"none"` (pump says no bolus running), `"requesting"`, `"delivering"`, `"done"`. Absent if unknown |
| `cb_u` | int | Requested amount of that bolus, milliunits (not the delivered amount) |
| `cb_pt` | int | Pump's time for that bolus (epoch s), if reported |
| `cb_t` | int | When the phone received the current-bolus status |
| `br`, `br_t` | int, int | *Reserved:* current delivered basal rate, milliunits/hour. Not sent until the scale of PumpX2's `CurrentBasalStatusResponse` is verified against the pump screen |
| `lb_u`, `lb_pt`, `lb_t` | int | *Reserved:* last completed bolus amount (milliunits), pump time, receive time. Not sent until `LastBolusStatus` is read and verified |

**Watch freshness rule (proposed; tune after the first pump sessions):** age = watch now − `*_t`. Up to 10 min: show normally with its age. 10–60 min: show marked stale with its age. Over 60 min, more than 5 min in the future, or absent: show `--`. `link` only labels the source; it never makes old values look current.

### 6.3 `glucose` v1 (watch → phone)

Sent once per new G7 reading (new sequence), via Connect IQ `Communications.transmit`. Small: under 200 bytes. Fields map to what the watch already decodes from the G7 `0x4E` message.

| Key | Type | Meaning |
|---|---|---|
| `t`, `v` | string, int | `"glucose"`, `1` |
| `tx` | string | Sensor's BLE name as the watch stores it (`g7DeviceName`, ≤ 8 chars) |
| `sq` | int | G7 reading sequence number; (`tx`, `sq`) is the deduplication key |
| `mg` | int | Glucose, mg/dL (raw & 0x0FFF). Absent if the sensor reported none (0xFFFF) |
| `st` | int | Reading time (epoch s) = watch time at receipt − the sensor's reported age |
| `rt` | int | Watch time the reading was received (epoch s) |
| `tr` | int | Sensor trend, tenths of mg/dL per minute (signed). Absent if unknown |
| `pr` | int | Sensor predicted glucose, mg/dL. Optional |
| `alg` | int | G7 algorithm state (6 = OK) |
| `do` | bool | Display-only flag from the sensor (calibration byte bit 0x10) |
| `rel` | bool | True only if the watch itself would show it as current: `alg == 6`, not display-only, value present, age ≤ 900 s. Same rule as the watch's dashboard |

**Phone rules:** store or display a reading only if `rel` is true, `mg` is within 40–400, `st` is at most 5 min in the future and at most 24 h old, and (`tx`, `sq`) is new. Label it as coming from the watch's direct G7 connection. Never send it back to the watch (no echo loops) and never treat it as a pump value.

**Implemented on the phone (2026-10-25):** `garmin/GarminWatchMessages` builds `pump_status` and parses/deduplicates `glucose` exactly as above; `GarminWatchMessagesTest` (15 tests) covers both. `GarminBridge.sendPumpStatus` sends it; the Tandem test screen has a "Send pump status to watch" button. **Watch → phone built (2026-10-25, Claude Code - garmin7-scout-watch-integrator, at the owner's request after the xDrip-Watch session stopped):** garmin7-scout `ScoutGlucoseSender` sends one `glucose` v1 message per new accepted reading (`Communications.transmit`). `GarminBridge.startListening` (started 5 s after launch in `.garminbridge` dev builds, and from the bridge test screen's "Listen for watch glucose") registers for the watch app's events, applies `parseGlucose` and `Deduper`, and stores accepted readings with `BgReading.bgReadingInsertFromG5(mg, st, "Garmin watch")`, which needs a started sensor in xDrip. xDrip's screens, alerts and local web service (read by phone-fed Garmin faces and widgets) then show it. No glucose echo or pump-status response is sent back to the watch; separate phone→watch control/configuration messages (`key`, `collector`, `slot`) are independent. **Not implemented:** the watch-side `pump_status` receiver; not verified on hardware.

## 7. Data semantics and safety

- **IOB:** use an explicitly decoded pump-reported value where verified. Do not substitute xDrip's insulin-action model without clearly marking it as a separate estimate.
- **Basal:** distinguish programmed profile rate, commanded/requested rate, actual delivered basal, temporary rates, and automated changes. Do not label one as another.
- **Bolus:** distinguish requested, in-progress, delivered, partial, interrupted, cancelled and unknown only when source fields actually support that distinction. Current-bolus status is not automatically last completed bolus history.
- **Glucose:** the watch reads G7 directly. Reporting a reading back to xDrip is an additional sink, not a reason to replace the watch's own current sensor result with phone data.
- **Staleness:** unknown is not zero; stale data must remain visibly stale. Include source time and receive time where applicable.
- **No loops:** returned watch glucose must not be interpreted as a new physical sensor observation and echoed back as a new reading or duplicated in local history.
- **Pump command scope:** no basal/profile change, suspend/resume or other therapy-changing command in this work. The upstream library's existence of control methods is not permission to use them. **Exception (owner decision 2026-10-25):** an experimental phone-initiated bolus, behind the gates listed in `AGENTS.md` (off by default, dev builds only, explicit phone UI with typed amount and confirmation, hard cap, never from the watch or any automatic trigger). *Source-derived:* PumpX2 implements remote bolus for the t:slim X2 (its README: "insulin unit amounts only; carbs / auto-corrections not yet supported"), and ControlX2 uses it from phone and Wear OS. This is the community reverse-engineered protocol, not a Tandem third-party API; live behaviour on the owner's pump/firmware is unverified.
- **Local first:** no Tandem Source cloud dependency and no Nightscout upload is required. Optional syncing to an independently configured Nightscout server is separate future scope, not a prerequisite.

## 8. Gates and evidence

1. ~~Compile the pinned PumpX2 dependency and execute synthetic mapper tests.~~ Done 2026-10-25; see [status, section 9](Tandem_X2_Integration_Status.md#9-build-and-test-evidence-2026-10-25).
2. Keep any fresh-pairing or pump-connection experiments behind the documented owner-approved, reversible test plan; do not disturb the pump's active G7/therapy connection.
3. Validate IOB and basal semantics against pump display, and determine history message/event semantics.
4. Keep the message schema synchronized across this document, the Garmin watch docs, and both implementations whenever a field or ownership rule changes.
5. Add synthetic contract tests on phone and watch; separately validate the `0x03` preference and `0x02` fallback.
6. Only with owner approval, test the real pump and G7 under a reversible plan that preserves the pump↔G7 link and documents restoring the normal phone collector if required.

**Current truth (2026-10-27):** the phone/watch bridge implements `ping`, saved-key `key` handoff (with sensor name when known), `collector` v1 ownership synchronization (including the `both` mode), `slot` v1 preference messages, and the watch-to-phone `glucose` v1 sender/receiver path. Host/unit tests and builds are not physical-device proof. The phone-side `pump_status` v1 builder and manual send action exist, but the watch receiver/display and `pump_history` are not implemented. Tandem pump values/history and end-to-end phone/watch delivery remain unverified on physical hardware.
