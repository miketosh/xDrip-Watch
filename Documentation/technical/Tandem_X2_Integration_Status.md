# Tandem t:slim X2 integration — implementation status

**Last reviewed:** 2026-10-25  
**Source baseline inspected:** `garmin-bridge` at `2de12256cdc965acb1f3c0a7b06cf013aaf7c53a` (2026-10-24)  
**Tandem integration status:** Not implemented  
**Validation performed for this document:** Repository source/tree and documentation inspection only. No build, automated test run, pump connection, or physical Garmin test was performed as part of this documentation change.

## 1. Scope and current facts

The owner uses a Tandem t:slim X2, an Android phone running xDrip+, and a Garmin Connect IQ watch integration split across this repository and [garmin7-scout](https://github.com/miketosh/garmin7-scout).

The current `garmin-bridge` source includes a phone-side Garmin Connect IQ bridge, but no Tandem-specific provider, protocol implementation, pump-status model, Tandem API client, or Tandem-specific tests were found in the inspected tree.

## 2. Existing phone-side code

| Location | Observed role | What it does not establish |
|---|---|---|
| `app/src/main/java/com/eveningoutpost/dexdrip/garmin/GarminBridge.java` | Initializes Garmin Connect IQ messaging, sends `ping`, and has a `sendKey` path. | Does not read Tandem data. The key path is specifically the saved **Dexcom G7** key. |
| `app/src/main/java/com/eveningoutpost/dexdrip/garmin/GarminBridgeTestActivity.java` | Manual phone-side test screen with ping and a two-tap G7-key transfer control. | This is not an automated test and does not prove live pump or physical watch behavior. |
| `app/src/main/java/com/eveningoutpost/dexdrip/services/Ob1G5CollectionService.java` | Exposes `getKeksKeyForBridge()`, which reads a 16-byte saved G7 key after the collector knows the transmitter MAC. | This is a Dexcom sensor pairing detail, not a Tandem authentication or pump-data interface. |
| `app/src/main/java/com/eveningoutpost/dexdrip/insulin/InsulinManager.java` and `Insulin.java` | Existing insulin profile and insulin-action model infrastructure. | Must not be treated as evidence that pump-reported IOB or delivered-basal history exists. |
| `app/src/main/java/com/eveningoutpost/dexdrip/insulin/aaps/AAPSStatusHandler.java` | Processes AndroidAPS/Nightscout device-status messages; updates pump reservoir/battery status when those fields are present. | Does not establish a direct Tandem X2 connection or availability of all desired fields. |
| `app/src/main/java/com/eveningoutpost/dexdrip/wearintegration/ExternalStatusService.java` | Handles an external status-line string and extracts basal-rate/TBR-like information from that string. | Not a Tandem protocol parser; parsed text must not be represented as pump data unless its origin is independently established. |

## 3. Garmin bridge status is separate from Tandem status

The companion repo's bridge notes describe the following bring-up work, which must continue to be treated separately from Tandem support:

- **P1 — phone-to-watch message path:** the phone bridge has a ping test. The companion documentation reported this as built but not yet validated on hardware at the time of that note.
- **P2 — saved G7 key handoff:** the phone bridge can send the saved G7 key, and the watch has a saved-key authentication path. The companion documentation reports simulator evidence but leaves real-sensor/physical-watch behavior open.
- This is not Tandem support. A successful ping or G7 key handoff does not validate Tandem discovery, authentication, IOB, bolus history, basal history, or pump safety.

Treat those statuses as reported by the companion project's documentation, not as new physical-test evidence from this repository inspection. See the [Garmin bridge design notes](https://github.com/miketosh/garmin7-scout/blob/main/docs/XDRIP_WATCH_BRIDGE.md).

## 4. Where tests live

- `app/src/test/` contains phone-app unit-test sources.
- `app/src/androidTest/` contains Android instrumentation-test sources.
- `wear/src/test/` contains tests for the legacy Android Wear module. These tests are not the Garmin Connect IQ app tests.
- `GarminBridgeTestActivity` is a manual test UI in `app/src/main`, not a test under `app/src/test`.
- Garmin Connect IQ host/simulator and physical-watch tests belong in the separate `garmin7-scout` repository.
- No Tandem-specific unit/instrumentation tests were found in the inspected `garmin-bridge` tree.

The checked-in `.github/workflows/unit_test.yml` workflow is named “Unit Test” and runs `assembleProdRelease testProdReleaseUnitTest` for pushes and pull requests targeting `master`. Confirm CI trigger coverage and task names before relying on this workflow for future work on `garmin-bridge`.

## 5. Tandem implementation inventory

| Capability | Current state |
|---|---|
| Direct phone-to-t:slim X2 read-only Bluetooth client | Not implemented; feasibility and coexistence are unknown. |
| Tandem app local API/interface | No supported interface established by this repository inspection. |
| Tandem Source/cloud client | Not implemented; not selected for the local-first initial path. |
| Pump-reported IOB | Not implemented. |
| Bolus event/history parsing | Not implemented. |
| Programmed basal profile retrieval | Not implemented. |
| Actual delivered basal history | Not implemented. |
| Canonical Tandem snapshot/event model | Not implemented. |
| Tandem data persistence/refresh/stale handling | Not implemented. |
| Tandem status message to Garmin | Not implemented. |
| Tandem-specific settings and UI | Not implemented. |
| Tandem fixtures and automated tests | Not implemented. |

## 6. Next steps

1. Keep this work documentation-only until the local source/protocol feasibility is understood.
2. Inventory relevant in-repository xDrip data structures and existing phone-to-Garmin message handling.
3. Document the supported read-only source and the exact evidence for each field before adding a live provider.
4. Build a synthetic/fake provider plus canonical model and tests before connecting to the pump.
5. Obtain explicit owner approval for an active-pump experiment only after the official-app coexistence/rollback risks have been assessed.

See [the design document](Tandem_X2_Integration.md) and [the test plan](Tandem_X2_Integration_Test_Plan.md).
