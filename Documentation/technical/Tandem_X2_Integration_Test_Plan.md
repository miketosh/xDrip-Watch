# Tandem t:slim X2 integration — test plan

**Status:** Synthetic mapper tests pass locally (7/7, 2026-10-25; see [status, section 9](Tandem_X2_Integration_Status.md#9-build-and-test-evidence-2026-10-25)). The opt-in PumpX2 client has no UI caller and has not been tested against a physical pump.

## 1. Test principles

- Start locally with deterministic synthetic fixtures and a fake provider. No internet or live pump is needed for model/parser tests.
- Do not use real account credentials, authentication material, live protocol secrets, or identifiable pump history in committed fixtures.
- Do not connect a live pump until the read-only protocol, coexistence with the official Tandem app, and recovery procedure have been reviewed and the owner approves a controlled test.
- A successful compile, unit test, simulator message, or ping does not prove physical pump compatibility.
- Log test metadata and status transitions, not secrets or unnecessary health values.

## 2. Unit tests for the canonical model

Add tests before a live provider exists:

| Area | Required cases |
|---|---|
| Missing vs zero | Missing IOB stays unknown; explicit source-reported zero remains zero. |
| Provenance | Pump-reported, xDrip-estimated, and uploaded-history values remain distinct. |
| Units | Amount and rate fields have explicit units; invalid/missing units are rejected or marked unknown. |
| Timestamps | Correct epoch/time-zone conversion, missing source time, receive time, out-of-order events, and invalid timestamps. |
| Freshness | Current → stale → disconnected/error transitions; stale data cannot be surfaced as current. Thresholds must be configurable or derived from observed cadence and documented. |
| Event identity | Duplicate bolus/history records are deduplicated without deleting genuinely separate events. |
| Partial data | One malformed optional field does not corrupt otherwise valid fields; the whole snapshot fails safely when required identity/structure is invalid. |
| Basal meaning | Programmed basal and actual delivery are separate types/fields and cannot be accidentally substituted. |
| Delivery state | Confirmed, partial, cancelled, interrupted, and unknown states are preserved only when supplied by the source. |

## 3. Synthetic mapper tests added in the prototype

`app/src/test/java/com/eveningoutpost/dexdrip/tandem/TandemX2StatusMapperTest.java` adds synthetic response-object cases for:

- Control-IQ IOB mapping using the algorithm selected by the response.
- Unknown IOB algorithm remaining unavailable instead of becoming zero.
- Non-Control-IQ IOB milliunit-to-unit conversion.
- Basal raw-field preservation without assuming its scale.
- Current-bolus sentinel and requested-volume mapping.

**Automated test evidence (2026-10-25):** `gradlew :app:testFastDevUnitTest --tests 'com.eveningoutpost.dexdrip.tandem.*'`: 7 tests, 0 failures, on a Windows host with JDK 17. They check the mapping of synthetic PumpX2 response objects only, not live-pump semantics.

`libkeks/src/test/java/jamorham/keks/KeksBouncyCastleTest.java` (7 tests) guards the BouncyCastle 1.68 → 1.77 upgrade PumpX2 required: golden EC/hash/packet vectors recorded on 1.68, a two-party J-PAKE round trip, wrong-password and tampered-proof rejection, and an independently verified challenge signature. Run with `gradlew :libkeks:testDebugUnitTest`.

`app/src/test/java/com/eveningoutpost/dexdrip/tandem/TandemX2RequestPolicyTest.java` (9 tests, all passing 2026-10-25) covers the outgoing-message policy per connection mode: listen-only sends nothing, shared reads never send authentication, own pairing sends authentication and status reads; bolus, cancel-bolus, suspend and temp-rate requests are blocked in every mode; history requests are not allowlisted yet; pairing-code normalization; hex decoding of the saved secret; and the Timber guard.

`app/src/test/java/com/eveningoutpost/dexdrip/tandem/TandemX2BolusTest.java` (17 tests, all passing 2026-10-25) covers every bolus gate: amount parsing, the cap (default 5.0 U, cannot be widened past 25 U), release build, switch off, non-own-pairing modes, pump minimum and pump maximum, bolus messages absent from the status allowlist; and the session: exact captured units-only initiate shape, refusals send nothing, pump maximum stops before permission, denied permission, expired/settings-changed confirmation releases the permission, a second confirm sends no second bolus, an unanswered initiate becomes UNKNOWN, is never re-sent and blocks new requests until acknowledged, cancel before/after initiate, late permission released, foreign bolus ids ignored, sender down.

## 4. Provider/adapter tests

Use a fake local provider with fixed fixture input to verify:

- Successful read, empty response, delayed response, malformed response, timeout, disconnect, and reconnect.
- A source error does not overwrite a last-known good snapshot with fabricated zeroes.
- The update timestamp advances only after a valid response.
- Sensitive fields are not included in log messages or error text.
- No cloud/network request is made by the local-first provider path.
- Transport-specific data is converted to the canonical model at one boundary and does not leak into the Garmin message format.

When the actual source is selected, add sanitized, versioned fixtures derived from verified protocol observations. Record the pump model/firmware and the relevant app version in the fixture metadata, but do not commit unique identifiers or credentials.

## 5. Garmin sender/receiver contract tests

The phone and watch must agree on a versioned pump-status message before the feature is enabled:

- Valid complete snapshot and valid partial snapshot.
- Optional values absent/null, explicit zero, unknown status, and stale/disconnected state.
- Unit labels and source timestamp survive the transport intact.
- Unsupported message versions and malformed field types are rejected safely.
- Existing `ping` and Dexcom G7 `key` behavior remains unchanged.
- Pump status payloads never include a Tandem credential or raw authentication/protocol secret.
- Watch display makes stale/unavailable status visible and never implies that old data is current.

Keep current G7 pairing/authentication fixtures and Tandem fixtures separate.

## 6. Integration gates

### Pairing-code safety gate (must pass before fresh pairing is enabled)

**Status 2026-10-25 (Claude Code session `garmin7-scout-0a` (xDrip-Watch pump work)):** addressed in source, partly covered by tests. `TandemX2PumpClient.pair()` replaces PumpX2's `pair()` without its log lines; the client never calls `super.onPumpConnected()`; `start()` refuses while any Timber tree is planted (`TandemX2RequestPolicyTest.loggingGuardFailsClosedWhenATreeIsPlanted`); pairing is accepted only in `OWN_PAIRING` mode, and malformed codes are rejected (`pairingCodesAreNormalizedOrRejected`). Storage: PumpX2's `PumpState` keeps the code and derived secret in app-private SharedPreferences (same protection as xDrip's saved G7 key); clearing them is not implemented yet. **Not covered by tests:** the mode check inside the client (needs an Android runtime), and a captured-log check during a real pairing.

Original gate text: PumpX2 v1.9.3's current `TandemPump.pair()` logs the pairing code through Timber and persists it through `PumpState`. Before enabling fresh pairing:
- Remove or safely redact upstream pairing-code logging and review other authentication/protocol logs for key/challenge leakage.
- Document the intended local storage, retention, and clearing behavior for pairing code and derived authentication state.
- Add a deterministic test proving the adapter refuses pairing until the safety gate is enabled, and tests that no credential material appears in captured logs.
- Do not rely solely on passing a null Timber tree; another app/library may have installed a tree.



### Gate A — no-pump local tests

Pass criteria: all model/parser/provider tests pass with synthetic fixtures; no live network access or Bluetooth device is required. Verify that non-authentication PumpX2 requests outside the fixed read-only status allowlist are blocked.

### Gate B — Android phone bridge

Pass criteria: after the BLE provider has been separately verified, the phone can create a canonical snapshot and send a versioned status message to the watch receiver; sender and receiver logs confirm type/version/status only, without secrets. Test both absent and stale data. This prototype does not yet implement the Garmin status message.

### Gate C — simulator/watch display

Pass criteria: the Garmin app displays valid values with units, source/freshness, and safe unavailable states across reconnect/restart and malformed-message cases. Simulator evidence must be labelled simulator evidence.

### Gate D — approved controlled pump test

Do not begin until all are true:

1. A read-only source and its protocol actions are documented.
2. The owner has agreed to the exact test and rollback/recovery steps.
3. The test plan covers how the official Tandem app/pump connection is restored if disturbed.
4. No therapy-changing command is in scope.
5. The test does not depend on modifying pump settings or performing a bolus.

Record pump model/firmware, Tandem app version, phone Android version, xDrip build/commit, exact test sequence, data fields observed, source-to-display timing, disconnections, and recovery outcome. Do not commit sensitive raw captures.

#### Proposed first session: listen only (needs the owner's go-ahead)

Goal: learn whether xDrip sees any pump data while Tandem's app keeps the connection, without sending anything to the pump.

1. Phone setup unchanged: Tandem's app paired and connected to the pump as every day. Note pump firmware, Tandem app version and Android version.
2. Install the `fastDev` build (package `com.eveningoutpost.dexdrip.garminbridge`, side by side with the daily xDrip; it does not collect the G7 unless set up to).
3. Open "Garmin bridge test" → **Tandem pump test**. Pick the pump from the bonded-device buttons (the pump is bonded to the phone by Tandem's app). Leave **Listen only** selected. Press **Start**.
4. Open Tandem's app for a minute (it requests status when shown), then come back. Watch the screen for `CONNECTED: Listening…` and `Snapshot` lines; compare any IOB value with the pump's own screen.
5. Press **Stop** after about 10 minutes, or at once if Tandem's app shows any connection problem.

Pass: Tandem's app works throughout and after; any IOB shown matches the pump. Expected-uncertain: no snapshot at all (Tandem's app may not request these fields, or Android may not deliver another app's notifications). Recovery: listen-only sends no pump protocol message, so stopping (or force-stopping the test app) is the full rollback; if Tandem's app still misbehaves, force-stop it and reopen it. Record what the screen showed (it is not written to any log).

#### Proposed first bolus session (needs the owner's explicit go-ahead)

Preconditions: an own-pairing session has already shown status reads (IOB matches the pump); the owner chooses the time, has the pump in hand, and has decided the dose is one they would take anyway. This is a therapy action on an experimental, reverse-engineered path: the pump screen is the only source of truth.

1. Own pairing as in the status session (Tandem's app displaced; re-pair it afterwards).
2. Tick **Enable experimental bolus**; keep or lower the cap (default 5.00 U).
3. Type the smallest useful amount (for example 0.05–0.10 U) and press **Request bolus**. Expect `CHECKING_PUMP` → `AWAITING_PERMISSION` → `READY_TO_CONFIRM`. Nothing is delivered in these steps.
4. Press **Deliver … U now** within 30 s. Expect `SENDING` → `DELIVERING`, then `DONE` when the pump reports no current bolus. Watch the pump screen throughout.
5. Check the pump's bolus history for the exact amount. Optionally repeat steps 3–4 and press **Cancel bolus** during delivery to test cancel.
6. Untick the switch, Stop, re-pair Tandem's app.

Stop at once and use the pump itself if anything differs from the pump's own screen. If the screen shows `UNKNOWN`, read the pump's history before doing anything else; the app never re-sends. Record build/commit, pump firmware, every state line, the pump-history amount and timing.

### Gate E — repeatable end-to-end evidence

Pass criteria: multiple read-only sessions show the expected fields and freshness behavior on the actual phone/pump/watch combination; source provenance is confirmed; the official pump app remains recoverable; no therapy commands are sent; logs and docs match the observed behavior.

## 7. Existing test tasks and boundaries

The repository's current CI workflow runs:

```bash
./gradlew assembleProdRelease testProdReleaseUnitTest
```

The fork's local Garmin bridge work has also used:

```bash
./gradlew :app:assembleFastDev
```

Verify available Gradle variants before running these commands. `.github/workflows/unit_test.yml` is configured for pushes and pull requests targeting `master`; it does not by itself guarantee CI execution for every other branch.

Test source locations:

- `app/src/test/` — phone unit tests.
- `app/src/androidTest/` — phone instrumentation tests.
- `wear/src/test/` — legacy Android Wear unit tests, not Garmin Connect IQ tests.
- `GarminBridgeTestActivity` — manual bridge test screen, not an automated test.
- `garmin7-scout` — Garmin Connect IQ host/simulator and physical-watch validation.

## 8. Reporting results

Every test report must state the source commit/build, command or manual procedure, result, and environment. Clearly label the evidence as unit, integration, emulator, Connect IQ simulator, or physical-device evidence. A test that was not run must be marked not run. A fixture test passing is not evidence that the real Tandem source uses the same fields or semantics.
