# Tandem t:slim X2 integration — design and requirements

**Status:** Design in progress. Documentation only; no Tandem transport or data integration is implemented in this repository as of the inspected `garmin-bridge` baseline (`2de12256`, 2026-10-24).

**Target environment:** Tandem t:slim X2 pump, Android phone running the xDrip-Watch fork / xDrip+, and a Garmin Connect IQ companion app maintained in [garmin7-scout](https://github.com/miketosh/garmin7-scout).

## 1. Purpose

Add a local-first, read-only path for Tandem pump status and history into the existing phone-to-Garmin integration. The intended value is a Garmin glanceable view of pump-reported insulin on board (IOB), recent boluses and their delivery status, basal-rate information, and data freshness. More detailed event history can be considered after a reliable live snapshot is established.

The source must remain explicit. A value reported by the pump, a value inferred from xDrip's treatment/insulin model, and a value imported from an uploaded history are not interchangeable.

## 2. Desired user-visible capabilities

### First useful snapshot

Subject to source/API support and validation, the first useful watch status should present:

- Pump-reported IOB, including units and the time the source says it was calculated or observed.
- Most recent bolus amount and timestamp; delivery status should be shown only when the source reports it.
- Current basal delivery rate, if the source exposes actual delivery reliably. A programmed profile rate must be labelled separately and must not be presented as actual delivered basal.
- Pump/source connectivity state and last successful update time.
- Clear `unknown`, `stale`, `disconnected`, or `error` states rather than a plausible-looking empty/zero result.

The exact field set is gated by verification against the t:slim X2 protocol/source. Do not promise that a field is available until a local implementation or sanitized fixture proves it.

### History

The desired history view is phone-first, with compact recent information optionally sent to the watch:

- Bolus events: amount, event time, delivery state, and any source-provided distinction between requested, delivered, cancelled, or interrupted amounts.
- Basal history: programmed profile segments, actual basal delivery, temporary-rate events, suspension/resumption, and automated changes only where those categories are explicitly provided by the source.
- Source timestamp and ingestion timestamp for every event.

Do not infer an exact delivery history from a programmed schedule. Do not reconstruct “actual basal” from a scheduled rate. A history imported from a remote upload must be marked as uploaded/history data and not mistaken for live status.

## 3. Data-source strategy: local first, not yet transport-selected

No public, supported Tandem developer API or usable local interface has been established by this repository inspection. The following options are investigation tracks, not claims that a connection already works.

| Candidate | Role in plan | Main questions / limitations |
|---|---|---|
| Direct Android-to-pump Bluetooth | Preferred local-first candidate to investigate | Protocol and fields need to be established. Confirm whether another client can coexist with Tandem's official app, whether authentication changes connection ownership, and how to recover the supported app connection. Do not test against an active pump before this gate is passed. |
| Local interface exposed by Tandem's Android app | Preferred if an explicitly available, supported interface exists | No supported API or intent contract is established here. Do not assume app-private data can be accessed or scrape private files as a design premise. |
| Tandem Source / cloud history | Optional future fallback or history-only provider; not the initial path | Requires internet and account authentication; upload latency can make it unsuitable for current IOB or current basal status. Undocumented service behavior would need separate review and may break without notice. |
| xDrip's existing insulin/treatment model | A distinct estimate that may be useful for other display purposes | `InsulinManager` and the insulin profile classes model insulin action. They must not be presented as the pump's own IOB or pump-delivery record. |

**Decision for the initial implementation:** build and test a transport-independent data model and provider boundary first. Investigate local access in the repository and through controlled, read-only protocol research before selecting the concrete provider. Do not add a cloud dependency or live Bluetooth client as part of documentation work.

## 4. Proposed architecture

Keep these responsibilities separate:

```text
Tandem source adapter (not implemented)
        |
        v
Canonical PumpSnapshot + PumpEvent
        |
        v
Validation, source provenance, timestamps, freshness
        |
        v
Local repository/cache (scope and retention TBD)
        |
        +----> Phone status/history UI (later, if needed)
        |
        v
Versioned GarminBridge pump-status message (not implemented)
        |
        v
garmin7-scout phone-message receiver / watch display
```

### Provider boundary

A future interface should allow a simulated provider and a live provider to return the same canonical model. The selected transport must not leak protocol-specific packets into the watch message. The first design should support:

- A current snapshot with optional fields.
- Zero or more historical events.
- Source identity/type and source timestamp.
- Local receive time and last-successful-update time.
- A connection/data quality state.
- Explicit missing/invalid fields.

A later change may add names such as `TandemX2Provider` or `PumpSnapshot`; these are design concepts only, not existing classes.

### Phone-to-watch messages

- Extend `GarminBridge` with a new, versioned pump-status message type. Do not overload the existing `ping` or Dexcom G7 `key` message.
- Start by sending a compact latest snapshot, not an unrestricted stream of raw history or protocol payloads.
- Keep optional fields optional and preserve a clear distinction among zero, missing, invalid, and stale.
- Coordinate the exact message contract with `garmin7-scout` and add sender/receiver contract tests before enabling the feature by default.
- Garmin Connect IQ messaging is the existing transport to the watch; it has its own availability and delivery constraints. “Local-first” describes Tandem data acquisition and does not claim the Garmin relay is independent of Garmin Connect.

## 5. Field semantics and freshness

Every displayed datum must carry or be associated with:

- **Value and units.** For example, IOB and delivered bolus are insulin units; basal rates are units/hour. Do not silently convert or infer units.
- **Provenance.** At minimum: pump-reported, xDrip-estimated, or uploaded history. These must not be mixed.
- **Source time.** The event/measurement time reported by the source, if supplied.
- **Receive time.** When this phone instance received or parsed the data.
- **Freshness/state.** Current, stale, unavailable, disconnected, or error. The thresholds must be chosen from observed update behavior and documented rather than invented.
- **Validity.** An unknown value is null/unavailable, never fabricated as zero. An actually reported zero is valid only when the source explicitly reports it.

The initial implementation must not calculate pump IOB from bolus history as an automatic fallback. If pump-reported IOB cannot be obtained, show it as unavailable; a separately labelled xDrip estimate can be considered only as a distinct opt-in field.

## 6. Safety, privacy, and connection ownership

- The feature is read-only at the therapy level. No bolus, basal, profile, suspension, resume, or Control-IQ-setting commands.
- Do not change the pump's configuration, therapy, or official phone-app configuration.
- Determine whether direct BLE can coexist with the official Tandem app before enabling live communication. A second client might compete for the connection; coexistence is an open question, not an assumed fact.
- Keep protocol/authentication secrets out of logs, source control, test output, and Garmin messages unless a separately reviewed protocol design requires a specific protected transfer. Never transfer a Tandem secret merely by analogy with the existing G7 saved-key handoff.
- Keep data local by default. No Tandem account credentials, pump histories, or IOB values should be uploaded to a new service as part of the first implementation.
- Use sanitized, synthetic data in committed fixtures and screenshots. The watch should show the age/state of data so disconnection cannot masquerade as a live reading.
- This feature is for personal information display, not insulin dosing or therapy decisions.

## 7. Phased implementation gates

| Gate | Work | Evidence required to advance |
|---|---|---|
| T0 — Documentation and source inventory | Record existing phone/watch architecture, current insulin models, test locations, candidate local sources, and safety boundaries. | Docs agree with checked source; no code claims are implied. |
| T1 — Local protocol/source feasibility | Determine which read-only source is available locally and the exact fields it exposes. Investigate official-app coexistence and recovery without altering pump therapy. | Source-derived protocol notes or sanitized fixtures; documented uncertainty and a safe hardware-test plan. |
| T2 — Canonical model + fake provider | Define snapshot/event types, units, timestamps, provenance, and freshness. | Unit tests for missing values, reported zero, time conversion, duplicates, stale data, and malformed input. No pump required. |
| T3 — Read-only provider | Implement only verified status/history reads behind the provider boundary. | Test fixtures/regression tests plus explicit no-therapy-command review; hardware test only after owner approval. |
| T4 — Phone-to-watch snapshot | Add a new message contract and compact current status view. | Sender/receiver contract tests, simulator verification, then physical phone/watch verification. |
| T5 — History, polish, resilience | Add bounded event history, optional phone history view, reconnection/recovery, and settings. | Tests for interruption, restart, stale source, duplicates, partial data, and battery/background behavior. |

## 8. Open questions before live pump work

These questions do not block writing the design or implementing a fake provider, but they block an honest claim of live Tandem support:

1. What exact t:slim X2 hardware/firmware and Tandem mobile-app versions will be the first supported combination?
2. Which read-only local interface can provide each target field, and can it coexist with the official Tandem app without disrupting its connection?
3. Does the source report IOB directly? Which fields provide actual delivered basal vs programmed basal, and how are interrupted/partial boluses represented?
4. What update cadence and disconnect/staleness thresholds are supported by observation?
5. Which compact fields should be visible on the Garmin, and should detailed history remain phone-only initially?
6. What is the owner-approved hardware test and rollback/recovery procedure if a Bluetooth experiment disrupts the official app?

## 9. Current status

See [Tandem integration status](Tandem_X2_Integration_Status.md) and [test plan](Tandem_X2_Integration_Test_Plan.md). Current state is documentation/design only. The existing Garmin P1/P2 work is a separate G7 bridge; it is not Tandem support.
