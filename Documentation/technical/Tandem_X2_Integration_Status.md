# Tandem t:slim X2 integration — implementation status

**Last reviewed:** 2026-10-25  
**Source baseline before implementation:** `79096f07c059d8df09da87d805a0d80cc9813932` (2026-10-25)  
**Tandem integration status:** Opt-in adapter prototype; no end-to-end functionality verified  
**Validation performed:** PumpX2 source/API inspection and synthetic mapper tests added. No Gradle build/test, live pump connection, or physical Garmin test was run in this environment.

## 1. Scope and current facts

The owner uses a Tandem t:slim X2, an Android phone running xDrip+, and a Garmin Connect IQ watch integration split across this repository and [garmin7-scout](https://github.com/miketosh/garmin7-scout).

The `garmin-bridge` source includes a phone-side Garmin Connect IQ bridge. This iteration adds the pinned PumpX2 community library, `TandemX2PumpClient`, a transport-independent `TandemX2PumpSnapshot`, a response mapper, and synthetic mapper test sources. The client is opt-in and has no UI call site; no real pump communication has been verified. Existing xDrip Nightscout components are present in the base app, but the Tandem prototype is not connected to them.

## 2. Existing phone-side code

| Location | Observed role | What it does not establish |
|---|---|---|
| `app/src/main/java/com/eveningoutpost/dexdrip/garmin/GarminBridge.java` | Initializes Garmin Connect IQ messaging, sends `ping`, and has a `sendKey` path. | Does not read Tandem data. The key path is specifically the saved **Dexcom G7** key. |
| `app/src/main/java/com/eveningoutpost/dexdrip/garmin/GarminBridgeTestActivity.java` | Manual phone-side test screen with ping and a two-tap G7-key transfer control. | This is not an automated test and does not prove live pump or physical watch behavior. |
| `app/src/main/java/com/eveningoutpost/dexdrip/services/Ob1G5CollectionService.java` | Exposes `getKeksKeyForBridge()`, which reads a 16-byte saved G7 key after the collector knows the transmitter MAC. | This is a Dexcom sensor pairing detail, not a Tandem authentication or pump-data interface. |
| `app/src/main/java/com/eveningoutpost/dexdrip/insulin/InsulinManager.java` and `Insulin.java` | Existing insulin profile and insulin-action model infrastructure. | Must not be treated as evidence that pump-reported IOB or delivered-basal history exists. |
| `app/src/main/java/com/eveningoutpost/dexdrip/insulin/aaps/AAPSStatusHandler.java` | Processes AndroidAPS/Nightscout device-status messages; updates pump reservoir/battery status when those fields are present. | Does not establish a direct Tandem X2 connection or availability of all desired fields. |
| `app/src/main/java/com/eveningoutpost/dexdrip/tandem/TandemX2PumpClient.java` | Opt-in PumpX2 BLE client with a target-MAC filter and a request allowlist for authentication plus selected current-status reads. | Fresh pairing is intentionally blocked: upstream `TandemPump.pair()` logs and persists the pairing code. No UI caller, physical pump test, history retrieval, or Garmin status delivery. |
| `app/src/main/java/com/eveningoutpost/dexdrip/tandem/TandemX2PumpSnapshot.java` and `TandemX2StatusMapper.java` | Partial snapshot and mapping of two IOB response types, basal raw fields, and current-bolus status. | Synthetic code only. Basal rate scale and response values are not validated against a pump display. |
| `app/src/main/java/com/eveningoutpost/dexdrip/wearintegration/ExternalStatusService.java` | Handles an external status-line string and extracts basal-rate/TBR-like information from that string. | Not a Tandem protocol parser; parsed text must not be represented as pump data unless its origin is independently established. |

## 3. Garmin bridge status is separate from Tandem status

The companion repo's bridge notes describe the following bring-up work, which must continue to be treated separately from Tandem support:

- **P1 — phone-to-watch message path:** the phone bridge has a ping test. The companion documentation reported this as built but not yet validated on hardware at the time of that note.
- **P2 — saved G7 key handoff:** the phone bridge can send the saved G7 key, and the watch has a saved-key authentication path. The companion documentation reports simulator evidence but leaves real-sensor/physical-watch behavior open.
- This is not Tandem support. A successful ping or G7 key handoff does not validate Tandem discovery, authentication, IOB, bolus history, basal history, or pump safety.

Treat those statuses as reported by the companion project's documentation, not as new physical-test evidence from this repository inspection. See the [Garmin bridge design notes](https://github.com/miketosh/garmin7-scout/blob/main/docs/XDRIP_WATCH_BRIDGE.md).

## 4. Where tests live

- `app/src/test/` contains phone-app unit-test sources, including `TandemX2StatusMapperTest` (source added; execution not yet verified).
- `app/src/androidTest/` contains Android instrumentation-test sources.
- `wear/src/test/` contains tests for the legacy Android Wear module. These tests are not the Garmin Connect IQ app tests.
- `GarminBridgeTestActivity` is a manual test UI in `app/src/main`, not a test under `app/src/test`.
- Garmin Connect IQ host/simulator and physical-watch tests belong in the separate `garmin7-scout` repository.
- No Tandem-specific unit/instrumentation tests were found in the inspected `garmin-bridge` tree.

The checked-in `.github/workflows/unit_test.yml` workflow is named “Unit Test” and runs `assembleProdRelease testProdReleaseUnitTest` for pushes and pull requests targeting `master`. Confirm CI trigger coverage and task names before relying on this workflow for future work on `garmin-bridge`.

## 5. Tandem implementation inventory

| Capability | Current state |
|---|---|
| Direct phone-to-t:slim X2 read-only Bluetooth client | Opt-in `TandemX2PumpClient` prototype using PumpX2 `v1.9.3`; no UI call site or physical test. Coexistence remains unknown. |
| Tandem app local API/interface | No supported interface established by this repository inspection. |
| Tandem Source/cloud client | Not implemented; not selected for the local-first initial path. |
| Pump-reported IOB | Not implemented. |
| Bolus event/history parsing | Not implemented. |
| Programmed basal profile retrieval | Not implemented. |
| Actual delivered basal history | Not implemented. |
| Canonical Tandem snapshot model | Partial `TandemX2PumpSnapshot` exists for IOB, basal raw fields, and current bolus. Historical event model is not implemented. |
| Tandem data persistence/refresh/stale handling | Not implemented. |
| Tandem status message to Garmin | Not implemented. |
| Tandem-specific settings and UI | Not implemented; the client is not invoked by a screen or application startup. |
| Tandem fixtures and automated tests | Synthetic `TandemX2StatusMapperTest` source added; not run. No pump-derived fixture. |

## 6. Nightscout integration finding

The xDrip base app already contains `app/src/main/java/com/eveningoutpost/dexdrip/utilitymodels/NightscoutUploader.java` and `NightscoutTreatments.java`, alongside unit/integration tests for Nightscout upload behavior. These should be evaluated before introducing a second uploader. No Tandem response is currently mapped into xDrip's upload queue.

The upstream [tconnectsync](https://github.com/jwoglom/tconnectsync) repository already uploads Tandem Source cloud events to Nightscout; PumpX2 does not advertise a Nightscout uploader. The cloud path is not the selected local-first source. See [integration options](Tandem_Nightscout_Integration_Options.md).

## 7. Next steps

1. Run the new mapper tests and compile the app with the pinned PumpX2 dependency; fix any API/version issues.
2. Keep the client opt-in and unwired until a controlled hardware test is approved.
3. Verify IOB and basal field semantics against a t:slim X2 and its displayed values; do not equate current-bolus status with historical bolus history.
4. Add history requests only after current status reads and connection lifecycle are stable.
5. Obtain explicit owner approval for a live-pump test after official-app coexistence and rollback/recovery risks are assessed.

See [the design document](Tandem_X2_Integration.md) and [the test plan](Tandem_X2_Integration_Test_Plan.md).


## 7. Newly identified pairing-code safety blocker

Inspection of PumpX2 v1.9.3's `TandemPump.pair()` shows it emits a Timber INFO message containing the pairing code and stores the code through `PumpState` preferences. The wrapper intentionally refuses to pass a user-entered code into that method until log exposure and credential persistence have been reviewed and addressed. Passing a null logging tree to PumpX2 does not guarantee that another Timber tree is absent.

This is a deliberate functional limitation, not a passing connection test. The current prototype may expose the pairing-required state, but a fresh pairing flow must not be enabled yet. Existing xDrip/Tandem connection coexistence and recovery also remain unverified.
