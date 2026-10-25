# Tandem t:slim X2 integration — implementation status

**Last reviewed:** 2026-10-25  
**Source baseline before implementation:** `79096f07c059d8df09da87d805a0d80cc9813932` (2026-10-25)  
**Tandem integration status:** Opt-in adapter prototype; no end-to-end functionality verified  
**Validation performed:** PumpX2 source/API inspection; on 2026-10-25 the app compiles and assembles (`fastDev`) with PumpX2 after two build fixes, and the synthetic mapper tests pass (section 9). No live pump connection or physical Garmin test has been run.

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

- `app/src/test/` contains phone-app unit-test sources, including `TandemX2StatusMapperTest` (7/7 pass, 2026-10-25; section 9).
- `libkeks/src/test/` contains `KeksBouncyCastleTest`, the first libkeks (G7 J-PAKE) tests, added to cover the BouncyCastle upgrade PumpX2 required.
- `app/src/androidTest/` contains Android instrumentation-test sources.
- `wear/src/test/` contains tests for the legacy Android Wear module. These tests are not the Garmin Connect IQ app tests.
- `GarminBridgeTestActivity` is a manual test UI in `app/src/main`, not a test under `app/src/test`.
- Garmin Connect IQ host/simulator and physical-watch tests belong in the separate `garmin7-scout` repository.
- No Tandem-specific unit/instrumentation tests were found in the inspected `garmin-bridge` tree.

The checked-in `.github/workflows/unit_test.yml` workflow is named “Unit Test” and runs `assembleProdRelease testProdReleaseUnitTest` for pushes and pull requests targeting `master`. Confirm CI trigger coverage and task names before relying on this workflow for future work on `garmin-bridge`.

## 5. Tandem implementation inventory

| Capability | Current state |
|---|---|
| Direct phone-to-t:slim X2 read-only Bluetooth client | Opt-in `TandemX2PumpClient` prototype using PumpX2 `v1.9.3`, with three connection modes (section 10): listen only (default; shares Tandem's app's connection, sends nothing), shared reads, own pairing. Started only from the test screen; no physical test. Coexistence remains unknown. |
| Tandem app local API/interface | No supported interface established by this repository inspection. |
| Tandem Source/cloud client | Not implemented; not selected for the local-first initial path. |
| Pump-reported IOB | Not implemented. |
| Bolus event/history parsing | Not implemented. |
| Programmed basal profile retrieval | Not implemented. |
| Actual delivered basal history | Not implemented. |
| Canonical Tandem snapshot model | Partial `TandemX2PumpSnapshot` exists for IOB, basal raw fields, and current bolus. Historical event model is not implemented. |
| Tandem data persistence/refresh/stale handling | Not implemented. |
| Tandem status message to Garmin | v1 `pump_status` fields proposed ([contract §6.2](LOCAL_DATA_EXCHANGE_ARCHITECTURE.md)); phone builder `GarminWatchMessages` (tested) and a test-screen send button. Watch receiver not implemented; nothing sends automatically. |
| Tandem-specific settings and UI | Dev test screen `TandemX2TestActivity` (from the Garmin bridge test screen): pump picker from bonded devices, mode choice, start/stop, pairing code entry, on-screen state and snapshot. No settings; nothing starts at app startup. |
| Tandem fixtures and automated tests | Synthetic `TandemX2StatusMapperTest` passes (7/7, section 9). No pump-derived fixture. |
| Phone-initiated bolus (owner-approved experiment, 2026-10-25) | Implemented behind the `AGENTS.md` gates on the dev test screen (section 11); `TandemX2BolusTest` 17/17. Never sent to a pump. |

## 6. Nightscout integration finding

The xDrip base app already contains `app/src/main/java/com/eveningoutpost/dexdrip/utilitymodels/NightscoutUploader.java` and `NightscoutTreatments.java`, alongside unit/integration tests for Nightscout upload behavior. These should be evaluated before introducing a second uploader. No Tandem response is currently mapped into xDrip's upload queue.

The upstream [tconnectsync](https://github.com/jwoglom/tconnectsync) repository already uploads Tandem Source cloud events to Nightscout; PumpX2 does not advertise a Nightscout uploader. The cloud path is not the selected local-first source. See [integration options](Tandem_Nightscout_Integration_Options.md).

## 7. Next steps

1. ~~Run the new mapper tests and compile the app with the pinned PumpX2 dependency; fix any API/version issues.~~ Done 2026-10-25 (section 9).
2. Keep the client opt-in and unwired until a controlled hardware test is approved. Under the owner's 2026-10-25 decision, every pump session is a short supervised test after which Tandem's app is re-paired.
3. ~~Replace the blanket pairing refusal with a `pair()` override that logs neither the pairing code nor the J-PAKE derived secret (section 9), then add a dev-only phone test screen (pump MAC, pairing code, connect, show snapshot) for the first supervised session.~~ Done 2026-10-25 (section 10), plus connection modes that keep Tandem's app connected. Next physical step: the listen-only session in the [test plan](Tandem_X2_Integration_Test_Plan.md#proposed-first-session-listen-only-needs-the-owners-go-ahead), with the owner's go-ahead.
4. ~~Experimental phone bolus behind the `AGENTS.md` gates, with tests for each gate.~~ Coded 2026-10-25 (section 11). Physical use only after an own-pairing session has shown status reads work, and with the owner's explicit go-ahead for that bolus test ([test plan](Tandem_X2_Integration_Test_Plan.md#proposed-first-bolus-session-needs-the-owners-explicit-go-ahead)).
5. Verify IOB and basal field semantics against a t:slim X2 and its displayed values; do not equate current-bolus status with historical bolus history.
6. Add history requests only after current status reads and connection lifecycle are stable.
7. Obtain explicit owner approval for a live-pump test after official-app coexistence and rollback/recovery risks are assessed.

See [the design document](Tandem_X2_Integration.md) and [the test plan](Tandem_X2_Integration_Test_Plan.md).


## 7. Newly identified pairing-code safety blocker

Inspection of PumpX2 v1.9.3's `TandemPump.pair()` shows it emits a Timber INFO message containing the pairing code and stores the code through `PumpState` preferences. The wrapper intentionally refuses to pass a user-entered code into that method until log exposure and credential persistence have been reviewed and addressed. Passing a null logging tree to PumpX2 does not guarantee that another Timber tree is absent.

This is a deliberate functional limitation, not a passing connection test. The current prototype may expose the pairing-required state, but a fresh pairing flow must not be enabled yet. Existing xDrip/Tandem connection coexistence and recovery also remain unverified.

**Superseded 2026-10-25 (section 10):** the client now pairs through its own non-logging `pair()`, only in own-pairing mode, and refuses to start while a Timber tree is planted. Coexistence and recovery remain unverified.


## 8. Owner-confirmed end-state data paths (2026-10-25)

- t:slim X2 continues to talk directly to the Dexcom G7 using its pump/AID connection.
- xDrip's Tandem adapter is intended to obtain pump IOB/basal status and pull pump history locally. It remains prototype-only; fresh pairing is blocked and no real-pump validation is recorded.
- The Garmin continues to read glucose directly from the G7. Watch-to-phone glucose reporting is planned, not implemented.
- xDrip-to-watch pump status/bolus reporting is planned, not implemented.
- No Nightscout server upload is required. The Garmin path uses the Connect IQ phone-message bridge; xDrip's Android Wear Data Layer and Nightscout REST uploader are not Garmin substitutes.
- Preferred G7 authentication slot is watch `0x03`; if that path cannot be made reliable, app `0x02` is an acceptable final endpoint. When the watch uses `0x02`, xDrip must not simultaneously collect from the G7 in the final configuration. The pump's independent G7 connection must remain undisturbed.
- ~~Mobile bolus is out of scope. Whether any third-party API supports it is unverified; the current goal is read-only status and history.~~ Superseded 2026-10-25: the owner approved an experimental, gated phone-initiated bolus (`AGENTS.md`). PumpX2 implements the t:slim X2 remote-bolus messages (community reverse-engineered, not a Tandem API); behaviour on the owner's pump is unverified.
- Pump connection ownership (owner, 2026-10-25): Tandem's app stays the daily pump connection; xDrip pairs only in supervised test sessions, then Tandem's app is re-paired. Source: PumpX2 README (one phone app at a time; to verify on the pump).

See [the local data-exchange contract](LOCAL_DATA_EXCHANGE_ARCHITECTURE.md).

## 9. Build and test evidence (2026-10-25)

Claude Code session `garmin7-scout-0a` (xDrip-Watch pump work), Windows 11 host, JDK 17.0.20.1, Android SDK 36, Gradle wrapper of this branch. No phone, pump or watch involved.

**Before this change** (`5e84941`): `:app:compileFastDevJavaWithJavac` failed with 15 `cannot find symbol` errors (`BluetoothPeripheral`, `HciStatus`), then `cannot access Timber`. *Cause, observed in the published POMs:* `pumpx2-android:v1.9.3` declares `blessed-android:2.4.0` and `timber:5.0.1` with `runtime` scope, but `TandemX2PumpClient` uses their types in PumpX2's callback signatures.

**Second failure:** `:app:checkFastDevDuplicateClasses` — `pumpx2-messages` brings `bcprov-jdk14:1.77`, which duplicates xDrip/libkeks's `bcprov-jdk15to18:1.68`. Excluding the jdk14 jar alone is not enough: PumpX2 imports `org.bouncycastle.crypto.SavableDigest`, which the 1.68 jar does not contain (checked in the jar), so PumpX2 J-PAKE would fail at runtime.

**Changes:**
- `app/build.gradle`: explicit `blessed-android:2.4.0` and `timber:5.0.1` (the versions in PumpX2's POM); `bcprov-jdk14` excluded from the PumpX2 artifacts; `bcpkix-jdk15to18` 1.68 → 1.77.
- `libkeks/build.gradle`: `bcprov`/`bcpkix-jdk15to18` 1.68 → 1.77, the release PumpX2 was built against. The resolved `fastDevRuntimeClasspath` now has only `bcprov`, `bcpkix` and `bcutil-jdk15to18:1.77`.
- New `libkeks/src/test/java/jamorham/keks/KeksBouncyCastleTest.java`, because libkeks (xDrip's G7 J-PAKE, which the owner's live CGM uses) had no tests: golden P-256 point, zero-knowledge hash and packet encodings recorded on 1.68; a two-party J-PAKE round trip that must agree on the shared key; wrong-password and tampered-proof rejection; PKCS#8/SPKI key decoding; and the DSA challenge response checked with an independent `ECDSASigner`.

**Automated test evidence:**

| Command | BouncyCastle | Result |
|---|---|---|
| `:libkeks:testDebugUnitTest` | 1.68 (before upgrade) | 7/7 pass (golden values recorded from this run) |
| `:libkeks:testDebugUnitTest` | 1.77 | 7/7 pass, golden values unchanged |
| `:app:testFastDevUnitTest --tests 'com.eveningoutpost.dexdrip.tandem.*'` | 1.77 | `TandemX2StatusMapperTest` 7/7 pass |
| `:app:assembleFastDev` | 1.77 | `BUILD SUCCESSFUL`, `app-fast-dev.apk` |

**Not tested:** G7 collection on a phone with this build (the unit tests cover libkeks crypto, not the BLE flow), any PumpX2 runtime path, `prodRelease`/CI variants, a physical pump or watch.

**Pairing-code finding (source-derived, PumpX2 `v1.9.3` `TandemPump.pair()`):** besides the pairing code, the 6-digit (J-PAKE) branch also logs the **derived secret** (`PUMP-PAIR(SHORT_6CHAR, CONFIRM, pairingCode=…, derivedSecret=…)`), and that secret is reusable pump authentication material. xDrip's own source has no `Timber` calls or `Timber.plant`, and PumpX2 skips its debug tree when the handler gets a null tree, so these lines probably go nowhere in xDrip today (*inference; not checked at runtime*). `pair()` is public and non-final, and its building blocks (`PumpState`, `JpakeAuthBuilder`, `PumpChallengeRequestBuilder`) are public, so `TandemX2PumpClient` can override it with a version that does not log; that is the planned replacement for the current blanket refusal. `PumpState` keeps the code and derived secret in app-private SharedPreferences, the same protection as xDrip's saved G7 key.

## 10. Connection modes, safe pairing and test screen (2026-10-25)

Claude Code session `garmin7-scout-0a` (xDrip-Watch pump work). Same host and toolchain as section 9.

**Source-derived finding (PumpX2 `v1.9.3`, not in its README):** `TandemPump` can share the pump connection that the official Tandem app already has on the same phone. `enableTconnectAppConnectionSharing()` and `relyOnConnectionSharingForAuthentication()` make PumpX2 send no authentication message (sending one would make the pump drop the link the Tandem app is using, per the handler's comments); `enableSendSharedConnectionResponseMessages()` delivers the pump's replies to the Tandem app's requests to `onReceiveMessage`; `onlySnoopBluetoothAndBlockAllPumpX2Functionality()` blocks every PumpX2 write. So xDrip may be able to read pump data **while Tandem's app stays paired**, which would make continuous pump data compatible with the owner's 2026-10-25 decision (*to verify on the pump*). Bolus messages (`InitiateBolusRequest`, `CancelBolusRequest`, `BolusPermissionRequest`) are `signed=true` (HMAC with the pairing-derived secret), so an xDrip bolus needs own pairing.

**Changes:**
- `TandemX2ConnectionMode`: `LISTEN_ONLY` (default: sharing + PumpX2 snoop flag; sends no pump protocol message), `SHARED_READS` (sharing; sends the allowlisted status reads; PumpX2 also clears the qualifying-events characteristic itself, which may affect Tandem's app), `OWN_PAIRING` (xDrip authenticates; displaces Tandem's app). `TandemX2PumpClient.start()` sets all PumpX2 `PumpState` sharing flags for the mode, because they are process-wide statics.
- `TandemX2RequestPolicy` (pure Java): the per-mode outgoing-message rules, the Timber guard, pairing-code normalization (PumpX2's own `processPairingCode`, 6-digit codes must be digits) and hex decoding of the saved secret.
- `TandemX2PumpClient.pair()` overrides PumpX2's with the same steps minus its log lines, own-pairing mode only; `submitPairingCode()` now pairs instead of refusing. It tracks the pump model from `onPumpModel` (upstream keeps it private).
- `TandemX2TestActivity`, opened by a new "Tandem pump test" button on the Garmin bridge test screen (not exported, no launcher icon). Own pairing asks for a second tap. Values appear on screen only.

**Automated test evidence:** `:app:testFastDevUnitTest --tests 'com.eveningoutpost.dexdrip.tandem.*'` → `TandemX2RequestPolicyTest` 9/9, `TandemX2StatusMapperTest` 7/7; `:libkeks:testDebugUnitTest` → 7/7; `:app:assembleFastDev` succeeds.

**Emulator evidence (Android 16 / API 36 AVD `xdrip36`, no Bluetooth pump, `fastDev` build):** the bridge screen opened (Garmin Connect missing, as before) and its "Tandem pump test" button opened the test screen. Start with an empty address showed "A specific pump Bluetooth MAC address is required"; Start in listen-only with `00:11:22:33:44:55` showed `SCANNING … (LISTEN_ONLY)`; Stop showed `STOPPED`; own pairing showed the second-tap prompt (not pressed). No crash, and no PumpX2 log lines in logcat for the app process.

**Not tested:** any real pump; whether Android delivers another app's pump notifications to xDrip; how often Tandem's app requests the status fields; shared-reads and own-pairing behaviour; pairing on either code type; clearing the stored pairing state.

## 11. Experimental phone bolus (2026-10-25)

Claude Code session `garmin7-scout-0a` (xDrip-Watch pump work), owner-approved experiment ("go ahead and code up the feature"; cap "make it a setting, and default to 5.0U"). Same host and toolchain as section 9. **Never sent to a pump.**

**Source-derived protocol (PumpX2 `v1.9.3`, ControlX2 `HEAD`):** read `BolusCalcDataSnapshotRequest` (pump IOB, `maxBolusAmount` in milliunits) → `BolusPermissionRequest` → `BolusPermissionResponse` (granted + `bolusId`) → `InitiateBolusRequest(totalVolume, bolusId, typeBitmask, food, correction, carbs, BG, IOB)` → `InitiateBolusResponse`; cancel with `CancelBolusRequest(bolusId)`; give an unused permission back with `BolusPermissionReleaseRequest(bolusId)`. All bolus messages are `signed=true`, so they need xDrip's own pairing. PumpX2 also refuses `modifiesInsulinDelivery` messages until `enableActionsAffectingInsulinDelivery()` is called, and offers no way to switch that off again in the process. **Bolus type:** PumpX2 1.9.3 renamed the bits (ControlX2's `FOOD2` = 8 is now `CORRECTION` = 8); xDrip sends exactly what PumpX2's test captured from the pump for a "1u override bolus": type 8, all metadata 0.

**Implementation:**
- `TandemX2BolusPolicy`: gates in one function (release build, switch, own-pairing mode, amount format, 0.05 U minimum, cap, pump maximum); cap default 5.00 U, a setting outside 0.05–25 U falls back to the default; amount parsing to milliunits (≤ 2 decimals).
- `TandemX2BolusSession`: the sequence above. Only `request()` and `confirm()` (user actions) advance; confirmation window 30 s after the pump grants permission, then the permission is released; gates are re-checked at confirm; a second confirm sends nothing; no message is ever re-sent; no reply to the initiate within 10 s → `UNKNOWN`, which blocks new requests until the user acknowledges having checked the pump; late permission grants are released; replies for another bolus id are ignored.
- `TandemX2PumpClient.sendBolusStep()`: the only bolus send path, separate from the status allowlist (which still blocks every bolus message); re-checks release build, own-pairing mode, message type and connection; enables PumpX2's insulin switch only for the initiate. Bolus responses are routed to the session.
- Test screen: "Enable experimental bolus" (xDrip pref `tandem_x2_experimental_bolus_enabled`, default off), cap (pref `tandem_x2_experimental_bolus_cap_units`, default 5.00), amount, Request, Deliver (enabled only when ready), Cancel, "I checked the pump's history". Polls current-bolus status every 3 s while delivering. Hidden in release builds. Stop during a bolus warns that the pump may still be delivering.
- Not reachable from: the Garmin bridge, any intent/broadcast, app startup, alerts, automation.

**Automated test evidence:** `TandemX2BolusTest` 17/17, `TandemX2RequestPolicyTest` 9/9, `TandemX2StatusMapperTest` 7/7, `KeksBouncyCastleTest` 7/7; `:app:assembleFastDev` succeeds.

**Emulator evidence (API 36 AVD, no pump):** bolus section shown with cap 5.00 and the switch off; cap 30 refused ("Cap not saved"); in listen-only, Request → "experimental bolus is switched off", then with the switch on → "needs own-pairing mode"; in own pairing (scanning, no pump) → "could not send to the pump" and the client blocked the step. No crash.

**Not tested:** any pump interaction: permission, initiate, delivery, cancel, release, the real pump maximum, timing of replies, behaviour if the connection drops mid-bolus.
