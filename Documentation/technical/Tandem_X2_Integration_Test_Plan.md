# Tandem t:slim X2 integration — test plan

**Status:** Synthetic mapper-test source exists but has not been executed in this environment. The opt-in PumpX2 client has no UI caller and has not been tested against a physical pump.

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

These tests are source additions only; their execution has not yet been verified.

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
