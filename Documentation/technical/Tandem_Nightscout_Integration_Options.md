# Why Nightscout REST is not the local exchange protocol

**Status:** Reference note only; superseded by the [canonical local data-exchange architecture](LOCAL_DATA_EXCHANGE_ARCHITECTURE.md).  
**Updated:** 2026-10-25

## Decision

The intended feature does **not** need to upload pump information to a Nightscout server. Do not copy a Nightscout cloud uploader or make Nightscout hosting a dependency for the Tandem/Garmin integration.

Nightscout REST is an HTTP API for an application communicating with a configured Nightscout server. It is not the protocol for xDrip and a Garmin watch to exchange messages locally.

## Relevant existing projects and code

- [pumpX2](https://github.com/jwoglom/pumpX2) is the candidate local Bluetooth source for Tandem t:slim X2 status/history. It is experimental, reverse-engineered, and not an official Tandem SDK. History support and live-pump behavior require validation.
- [tconnectsync](https://github.com/jwoglom/tconnectsync) already maps Tandem Source **cloud** events into Nightscout entries. It is useful as a reference for how cloud history was represented, but its source events are not PumpX2 Bluetooth responses and its transport is not what this project needs.
- xDrip's WatchUpdaterService and SendToDataLayerThread use Google's Android Wear OS Data Layer for legacy Wear OS communication. That transport is distinct from Garmin Connect IQ.
- The existing GarminBridge uses Garmin's Connect IQ Mobile SDK on Android; the watch registers with Connect IQ Communications. This is the intended xDrip↔Garmin message transport. It has its own delivery constraints, but it does not require a Nightscout-server upload.
- ExternalStatusBroadcastReceiver / ExternalStatusService consume a compact, legacy Android intent/status-line contract. It is too ambiguous for the desired typed IOB, basal, bolus, history, timestamps, and freshness fields; do not adopt it as the new cross-device schema.

## Intended data movement

1. The t:slim X2 keeps its direct pump/AID connection to the Dexcom G7.
2. xDrip reads Tandem pump status and history locally through a validated read-only PumpX2 adapter.
3. The Garmin watch reads glucose directly from the G7.
4. xDrip sends pump status/recent bolus information to the watch through the versioned Garmin phone-message path.
5. The watch reports accepted direct-G7 glucose readings back to xDrip through the reverse message path.
6. No Nightscout-server upload is needed for these flows.

The watch slot/DisplayType 0x03 is preferred for direct G7 access. If it cannot be made reliable, app slot 0x02 is an acceptable final fallback provided xDrip no longer collects G7 directly while the watch owns that single-occupancy slot. The pump's own G7 connection remains intact.

## Implementation state

- PumpX2 adapter in xDrip-Watch: prototype only; real-pump behavior/history are not verified and fresh pairing is currently blocked by the documented pairing-secret logging/storage concern.
- Phone-to-watch ping and saved-G7-key message types exist as prototype paths.
- Phone-to-watch pump status/history and watch-to-phone glucose reading messages are not implemented end-to-end.
- Mobile bolus is out of scope. The availability of a third-party-supported mobile-bolus API is unknown; do not infer permission or availability from the existence of control methods in an experimental library.

For the canonical topology, slot selection policy, responsibility split, proposed messages, and evidence requirements, read [LOCAL_DATA_EXCHANGE_ARCHITECTURE.md](LOCAL_DATA_EXCHANGE_ARCHITECTURE.md).
