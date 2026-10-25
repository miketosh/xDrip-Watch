# Working agreement for xDrip-Watch

Read this file before changing code, tests, build configuration, or documentation. The project is an experimental personal fork of xDrip+ with a phone-to-Garmin Connect IQ bridge. Treat this file as the shared operating agreement for every coding assistant and contributor.

## Current phase and working branch

- The active Garmin bridge work is on `garmin-bridge`. Continue there with small, sequential, reviewable changes unless the owner asks to change the workflow. Do not force-push, reset, or overwrite another contributor's work.
- The baseline is **not yet proven on physical hardware**. A successful Android build, a Connect IQ simulator run, or a successful SDK `sendMessage` callback does not prove end-to-end phone-to-watch delivery or live G7 collection.
- The working tree of this fork is the phone-side xDrip Android app. The Garmin Connect IQ app and its simulator/device tests live in the separate [garmin7-scout repository](https://github.com/miketosh/garmin7-scout). Keep the phone and watch contracts documented in both places when they change.
- Current branch baseline inspected for the Tandem design: `2de12256` (2026-10-24). See [Tandem integration status](Documentation/technical/Tandem_X2_Integration_Status.md) for the exact scope of that inspection.

## Product goal and scope

- Preserve existing xDrip behavior while developing an explicit, versioned phone-to-Garmin data bridge.
- Planned Tandem support is for the owner's **Tandem t:slim X2** and Android phone. The desired output is read-only pump status and history data for the Garmin watch, with source and freshness information.
- Prefer a local phone-to-pump data path. Do not add a cloud dependency merely for convenience. Any cloud/Tandem Source path is a separately documented fallback proposal, not the default architecture.
- Until implementation and evidence exist, describe Tandem support as **planned / not implemented**. Do not imply that xDrip currently reads Tandem X2 data.
- This integration is for display and personal research only. It is not a medical device and must not be presented as a replacement for the pump display, Tandem's supported app, CGM alerts, or clinical advice.

## Safety invariants for any future pump work

- Keep the Tandem integration read-only at the therapy level. Do not implement or invoke bolus delivery, basal changes, profile changes, suspend/resume, Control-IQ configuration, calibration, or other therapy-changing operations.
- A read-only feature may still require transport setup or protocol authentication. Such protocol steps must be separately understood and documented; never assume that a Bluetooth write is harmless based only on its transport type.
- Never experiment against an active pump until the protocol path, connection ownership, fallback/recovery procedure, and risk to the official Tandem phone-app connection have been assessed and the owner explicitly agrees to the hardware test.
- Never report an unknown value as zero. Do not silently substitute xDrip's modeled IOB for a pump-reported IOB. Values must include provenance, timestamp, units, and stale/unknown handling.
- Never let stale pump data look current. If the source disconnects, fails, or stops updating, mark the data stale/unavailable and expose the last-update time.
- Do not log or commit pump credentials, authentication material, pairing secrets, access tokens, unredacted personal health data, or full live protocol captures. Use synthetic/redacted fixtures. The existing Dexcom G7 shared key sent by `GarminBridge` is sensitive and is **not** Tandem data.

## Documentation accuracy rules

- Separate these labels in shared Markdown:
  - **Observed in source** — confirmed by the current repository code.
  - **Automated test evidence** — a named test and its actual result.
  - **Simulator evidence** — observed in a named simulator/build.
  - **Physical-device evidence** — observed on a real, specified device/firmware and recorded with the test steps.
  - **Hypothesis / to verify** — plausible but not yet proven.
  - **Planned / not implemented** — desired behavior without implementation evidence.
- Do not promote a hypothesis to a fact because a build passes or because another project claims support. Record the exact source revision and model/firmware where relevant.
- Update the technical document and status/test plan in the same change as any feature or protocol change. If a code path is removed or replaced, revise the docs rather than leaving a historic experiment described as current.
- Prefer the repository's code, tests, fixture data, and existing documentation as the first source of truth. Do not introduce internet access at runtime for Tandem support. External research may be used when necessary, but record what it establishes and what remains unverified.

## Architecture boundaries

- `app/` is the Android phone app. The current bridge is under `app/src/main/java/com/eveningoutpost/dexdrip/garmin/`.
- `GarminBridge` currently supports bridge test messages and transfer of a saved **Dexcom G7** key to the Garmin app. Keep this separate from a future Tandem provider and from any Tandem status schema.
- `wear/` is the legacy Android Wear app module; its `wear/src/test/` tests are not the Garmin Connect IQ bridge tests.
- The Connect IQ watch implementation is in `miketosh/garmin7-scout`. Treat phone/watch messages as a versioned contract and coordinate changes with its documentation and tests.
- Keep pump transport, canonical pump data, freshness/validation, local persistence, and Garmin delivery as separate responsibilities. This allows transport experiments to be replaced without changing the watch contract.
- Do not reuse `InsulinManager`'s model-based insulin-action calculation as evidence of the pump's true reported IOB. Keep modeled and pump-reported quantities distinct.

## Required loop for each iteration

1. Read this file, the relevant documentation under `Documentation/technical/`, the touched source files, and applicable tests.
2. State one behavior or design question for the iteration and the evidence that would prove it.
3. Make the smallest coherent change. Do not bundle unrelated refactors with protocol experiments.
4. Add or update deterministic local tests before depending on hardware. Use synthetic fixtures that do not contain real credentials or personal health data.
5. Run the relevant unit tests/build available for the changed module. For broad branch validation, the checked-in CI workflow uses `assembleProdRelease testProdReleaseUnitTest`; the current fork's local Garmin bring-up has used `:app:assembleFastDev`. Verify task names against the checked-out Gradle variants rather than assuming.
6. For watch-message changes, update the matching phone and Garmin documentation and test both ends of the versioned contract.
7. Record what was actually run and its result. Explicitly say when a physical pump, Android phone, Garmin Connect, or real watch was not tested.

## Test locations and test meaning

- `app/src/test/`: phone-app unit tests.
- `app/src/androidTest/`: Android instrumentation tests.
- `wear/src/test/`: legacy Wear OS module tests; not a substitute for Garmin Connect IQ tests.
- `GarminBridgeTestActivity`: a manual phone-side bridge test screen, not an automated unit test.
- Garmin-side host/simulator and physical-watch evidence belongs in the separate `garmin7-scout` repository.
- Do not claim Tandem coverage until Tandem-specific deterministic tests exist. Do not connect to the live pump as a test shortcut.

## Definition of done

A change is complete when the code and docs agree, tests appropriate to the change have been run (or the limitation is recorded), logs avoid secrets/health-data leakage, and the update distinguishes verified behavior from unverified behavior. A documentation-only change must say that no implementation or hardware validation was performed.
