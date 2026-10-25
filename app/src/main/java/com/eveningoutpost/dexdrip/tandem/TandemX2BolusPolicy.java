package com.eveningoutpost.dexdrip.tandem;

import androidx.annotation.Nullable;

import com.jwoglom.pumpx2.pump.messages.Message;
import com.jwoglom.pumpx2.pump.messages.request.control.BolusPermissionReleaseRequest;
import com.jwoglom.pumpx2.pump.messages.request.control.BolusPermissionRequest;
import com.jwoglom.pumpx2.pump.messages.request.control.CancelBolusRequest;
import com.jwoglom.pumpx2.pump.messages.request.control.InitiateBolusRequest;
import com.jwoglom.pumpx2.pump.messages.request.currentStatus.BolusCalcDataSnapshotRequest;

import java.util.Locale;

/**
 * Gates for the owner-approved experimental phone bolus (xDrip-Watch AGENTS.md, 2026-10-25).
 * Pure Java so every gate is unit-tested. Units are milliunits throughout, as in PumpX2.
 */
public final class TandemX2BolusPolicy {
    private TandemX2BolusPolicy() {}

    /** PumpX2 InitiateBolusRequest.MIN_BOLUS_MILLIUNITS (0.05 U). */
    public static final long MIN_MILLIUNITS = 50;
    /** Upper bound for the cap setting: the t:slim X2's largest bolus is 25 U. */
    public static final long MAX_CAP_MILLIUNITS = 25_000;
    /** Owner decision 2026-10-25: the cap is a setting, default 5.0 U. */
    public static final long DEFAULT_CAP_MILLIUNITS = 5_000;
    /**
     * Bolus type of a units-only bolus, as captured from the pump in PumpX2's
     * InitiateBolusRequestTest ("1u override bolus": type 8, no food/correction/carbs/BG/IOB).
     */
    public static final int UNITS_ONLY_BOLUS_TYPE = 8;

    public enum Refusal {
        NONE("allowed"),
        RELEASE_BUILD("not available in release builds"),
        NOT_ENABLED("experimental bolus is switched off"),
        NOT_OWN_PAIRING("needs own-pairing mode (bolus messages are signed with xDrip's pairing)"),
        INVALID_AMOUNT("amount must be a number with at most two decimals"),
        BELOW_MINIMUM("below the pump minimum of 0.05 U"),
        ABOVE_CAP("above the bolus cap setting"),
        ABOVE_PUMP_MAXIMUM("above the pump's maximum bolus setting"),
        BUSY("a bolus is in progress, or its outcome is unknown and not yet acknowledged"),
        NOT_CONNECTED("could not send to the pump");

        public final String text;

        Refusal(String text) {
            this.text = text;
        }
    }

    /**
     * Every gate in one place. {@code pumpMaxMilliunits} is null until the pump's bolus
     * calculator snapshot has been read; the caller re-checks once it is known.
     */
    public static Refusal check(boolean releaseBuild, boolean enabled,
                                @Nullable TandemX2ConnectionMode mode,
                                @Nullable Long milliunits, long capMilliunits,
                                @Nullable Long pumpMaxMilliunits) {
        if (releaseBuild) return Refusal.RELEASE_BUILD;
        if (!enabled) return Refusal.NOT_ENABLED;
        if (mode != TandemX2ConnectionMode.OWN_PAIRING) return Refusal.NOT_OWN_PAIRING;
        if (milliunits == null) return Refusal.INVALID_AMOUNT;
        if (milliunits < MIN_MILLIUNITS) return Refusal.BELOW_MINIMUM;
        if (milliunits > sanitizeCap(capMilliunits)) return Refusal.ABOVE_CAP;
        if (pumpMaxMilliunits != null && milliunits > pumpMaxMilliunits) return Refusal.ABOVE_PUMP_MAXIMUM;
        return Refusal.NONE;
    }

    /** A cap outside 0.05–25 U falls back to the default rather than widening the limit. */
    public static long sanitizeCap(long capMilliunits) {
        if (capMilliunits < MIN_MILLIUNITS || capMilliunits > MAX_CAP_MILLIUNITS) {
            return DEFAULT_CAP_MILLIUNITS;
        }
        return capMilliunits;
    }

    /** "1.25" → 1250. Null for anything but a plain non-negative number with ≤ 2 decimals. */
    @Nullable
    public static Long parseMilliunits(@Nullable String text) {
        if (text == null) return null;
        final String s = text.trim();
        if (!s.matches("[0-9]{1,2}(\\.[0-9]{1,2})?|\\.[0-9]{1,2}")) return null;
        final String[] parts = s.split("\\.", -1);
        final long whole = parts[0].isEmpty() ? 0 : Long.parseLong(parts[0]);
        long fraction = 0;
        if (parts.length == 2) {
            fraction = Long.parseLong(parts[1]) * (parts[1].length() == 1 ? 100 : 10);
        }
        return whole * 1000 + fraction;
    }

    public static String formatUnits(long milliunits) {
        return String.format(Locale.US, "%.2f", milliunits / 1000.0);
    }

    /** Messages the bolus session may send; none of them is in the status allowlist. */
    public static boolean isBolusStep(@Nullable Message message) {
        return message instanceof BolusCalcDataSnapshotRequest
                || message instanceof BolusPermissionRequest
                || message instanceof InitiateBolusRequest
                || message instanceof CancelBolusRequest
                || message instanceof BolusPermissionReleaseRequest;
    }
}
