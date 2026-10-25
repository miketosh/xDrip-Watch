package com.eveningoutpost.dexdrip.tandem;

/**
 * How {@link TandemX2PumpClient} uses the pump's Bluetooth link. Android lets two apps on the
 * same phone share one connection to the pump; PumpX2 v1.9.3 supports riding on the official
 * Tandem app's authenticated connection ("t:connect app connection sharing").
 */
public enum TandemX2ConnectionMode {
    /**
     * Default. Shares the official Tandem app's connection and only listens to the pump's
     * replies to that app's own requests. Sends no pump protocol message (PumpX2
     * onlySnoopBluetooth). Android still opens a GATT client: service discovery, notification
     * enable, MTU and connection-priority requests.
     */
    LISTEN_ONLY,
    /**
     * Shares the official app's connection, leaves authentication to that app, and sends the
     * allowlisted status reads. PumpX2 also clears the qualifying-events characteristic on its
     * own in this mode, which may interfere with the official app (unverified).
     */
    SHARED_READS,
    /**
     * xDrip pairs and authenticates by itself. Per PumpX2's README this displaces the official
     * app, which must be re-paired afterwards. Signed commands (bolus) need this mode.
     */
    OWN_PAIRING
}
