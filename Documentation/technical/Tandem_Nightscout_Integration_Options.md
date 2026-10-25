> **Documentation update (2026-10-25): SUPERSEDED FOR ARCHITECTURE.** The project does not require a Nightscout cloud uploader. This file remains as upstream comparison/reference notes only. The canonical system design is [Local data exchange architecture](LOCAL_DATA_EXCHANGE_ARCHITECTURE.md). Do not implement the old optional-uploader recommendation as part of the current goal.

# Tandem X2 data and Nightscout integration options

**Reviewed:** 2026-10-25  
**Scope:** t:slim X2 data acquisition, existing xDrip Nightscout components, upstream PumpX2, and upstream tconnectsync. This is a design decision record, not evidence of a working pump connection.

## Decision summary

**Use PumpX2 as the candidate local pump-data source. Do not clone tconnectsync's cloud uploader into the Android app. This feature has no Nightscout-server upload requirement.**

- **PumpX2** provides a reverse-engineered local Bluetooth protocol library for t:slim X2/Mobi, including current-status responses and partially supported history parsing. Its README does not describe Nightscout uploading, and a source search found no Nightscout integration in that repository. It is the local-data option, but it is experimental and has therapy-changing APIs that must remain blocked by our wrapper.
- **tconnectsync** already implements Tandem-to-Nightscout synchronization, but it reads Tandem Source's undocumented cloud API. It can synchronize basal, bolus, IOB, pump events, and profiles; optional CGM upload is explicitly delayed by more than 30 minutes and is not intended as a real-time monitoring source. This is useful as a reference for Nightscout object semantics and history mapping, but it is not a cloud-free pump connection.
- **This xDrip codebase already has Nightscout infrastructure**, including `NightscoutUploader`, `NightscoutTreatments`, upload/download tests, and treatment/device-status concepts. Before adding another uploader, inspect and extend the existing xDrip path in a small isolated change.

## Comparison

| Project | Data source | Nightscout integration | Fit for the current goal |
|---|---|---|---|
| [pumpX2](https://github.com/jwoglom/pumpX2) | Local Bluetooth to pump | No Nightscout uploader found in its README/source search | Best candidate for local current status. Its read-only field coverage and live-pump behavior still require validation. |
| [tconnectsync](https://github.com/jwoglom/tconnectsync) | Tandem Source cloud API | Complete one-way data synchronization into Nightscout | Good reference for basal/bolus/event/profile object mapping, but introduces cloud/account dependencies and upload latency. |
| [xDrip-Watch](https://github.com/miketosh/xDrip-Watch) | Existing xDrip collectors/models plus proposed PumpX2 adapter | Existing general-purpose Nightscout upload/download components are already in the app | Prefer adapting verified pump data to existing xDrip models/uploader, with user-configured Nightscout upload remaining separate from local Garmin display. |

## What is reusable from tconnectsync?

The upstream `tconnectsync` repository includes a Nightscout client and explicit mapping of Tandem Source events to Nightscout basal and bolus entries. The most useful parts to study are:

- `tconnectsync/nightscout.py`: HTTP/API behavior for Nightscout.
- `tconnectsync/parser/nightscout.py`: Nightscout entry shapes.
- `tconnectsync/sync/tandemsource/process_bolus.py`: mapping completed cloud bolus events to treatment entries.
- `tconnectsync/sync/tandemsource/process_basal.py`: mapping basal events and durations.
- `tconnectsync/sync/tandemsource/update_profiles.py`: profile representation.

These processors consume Tandem Source event records, **not** PumpX2 Bluetooth response objects. They are not drop-in adapters for the local BLE stream. A port must preserve source timestamps, event IDs/deduplication, partial/cancelled delivery semantics, time zones, and basal-duration rules. Do not assume programmed basal rate and actual delivered basal are interchangeable.

Both upstream projects publish MIT licenses. If code is copied rather than independently implemented from behavior, preserve the applicable copyright/license notice and review all dependency/version interactions. This prototype currently copies no tconnectsync code.

## Recommended architecture

1. Keep local pump reads behind `TandemX2PumpClient` and map only validated PumpX2 response types into the canonical phone-side model.
2. Keep the Garmin status path local and independent of Nightscout. A Nightscout outage must not prevent a local snapshot from being shown.
3. If Nightscout upload is desired, first determine whether a verified event can be represented using xDrip's existing treatment/device-status models and queued uploader. Avoid creating a second upload client unless the existing path demonstrably cannot represent the needed data.
4. Do not upload a current snapshot as a fabricated historical event. Only pump events with reliable timestamps and semantics should become Nightscout treatment/history entries. IOB and basal current status require explicit field/type decisions before upload.
5. Make any additional Nightscout upload behavior user-configurable and covered by local tests; never require Tandem Source credentials for the local Bluetooth path.

## Current state and non-goals

- The PumpX2 adapter prototype currently maps selected IOB responses, current basal status (raw/unscaled fields), and current-bolus status.
- The current-bolus response is not historical last-bolus history.
- No history stream or Nightscout upload has been wired into the prototype.
- No live pump or end-to-end Nightscout test has been performed.
- This document does not recommend using Tandem Source cloud data for real-time insulin information.
