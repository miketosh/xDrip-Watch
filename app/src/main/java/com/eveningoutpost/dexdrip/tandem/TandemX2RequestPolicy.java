package com.eveningoutpost.dexdrip.tandem;

import androidx.annotation.Nullable;

import com.jwoglom.pumpx2.pump.messages.Message;
import com.jwoglom.pumpx2.pump.messages.builders.PumpChallengeRequestBuilder;
import com.jwoglom.pumpx2.pump.messages.request.currentStatus.ApiVersionRequest;
import com.jwoglom.pumpx2.pump.messages.request.currentStatus.ControlIQIOBRequest;
import com.jwoglom.pumpx2.pump.messages.request.currentStatus.CurrentBasalStatusRequest;
import com.jwoglom.pumpx2.pump.messages.request.currentStatus.CurrentBolusStatusRequest;
import com.jwoglom.pumpx2.pump.messages.request.currentStatus.NonControlIQIOBRequest;
import com.jwoglom.pumpx2.pump.messages.request.currentStatus.PumpVersionRequest;
import com.jwoglom.pumpx2.pump.messages.request.currentStatus.TimeSinceResetRequest;

import timber.log.Timber;

/**
 * Pure safety rules for {@link TandemX2PumpClient}, kept free of Android types so they can be
 * unit-tested on the JVM.
 */
public final class TandemX2RequestPolicy {
    private TandemX2RequestPolicy() {}

    static final String AUTH_REQUEST_PACKAGE =
            "com.jwoglom.pumpx2.pump.messages.request.authentication";

    /**
     * Whether the client may send this message in this mode. Listen-only sends nothing;
     * authentication messages only go out when xDrip owns the pairing; everything else must be
     * one of the fixed read-only status requests.
     */
    public static boolean isAllowed(@Nullable TandemX2ConnectionMode mode, @Nullable Message message) {
        if (mode == null || message == null || message.getClass().getPackage() == null) return false;
        if (mode == TandemX2ConnectionMode.LISTEN_ONLY) return false;
        if (AUTH_REQUEST_PACKAGE.equals(message.getClass().getPackage().getName())) {
            return mode == TandemX2ConnectionMode.OWN_PAIRING;
        }
        return isStatusRead(message);
    }

    static boolean isStatusRead(Message message) {
        return message instanceof ApiVersionRequest
                || message instanceof PumpVersionRequest
                || message instanceof TimeSinceResetRequest
                || message instanceof CurrentBasalStatusRequest
                || message instanceof CurrentBolusStatusRequest
                || message instanceof ControlIQIOBRequest
                || message instanceof NonControlIQIOBRequest;
    }

    /**
     * PumpX2 logs every parsed message (health data) and, in TandemPump.pair() and
     * onPumpConnected(), the pairing code and J-PAKE derived secret, all through Timber. xDrip
     * plants no Timber tree, so those lines go nowhere; refuse to start if anything has planted
     * one.
     */
    public static boolean isPumpX2LoggingSilent() {
        return Timber.treeCount() == 0;
    }

    /**
     * Pairing code as PumpX2 expects it (whitespace and dashes removed): 6 digits on newer
     * firmware, 16 characters on older firmware. Null if it is neither.
     */
    @Nullable
    public static String normalizePairingCode(@Nullable String code) {
        if (code == null) return null;
        try {
            final String processed = PumpChallengeRequestBuilder.processPairingCode(code);
            if (processed.length() == 6 && !processed.matches("[0-9]{6}")) return null;
            return processed;
        } catch (PumpChallengeRequestBuilder.InvalidPairingCodeFormat | RuntimeException e) {
            return null;
        }
    }

    /** Decodes PumpState's hex-encoded derived secret; null if blank or malformed. */
    @Nullable
    static byte[] hexToBytes(@Nullable String hex) {
        if (hex == null) return null;
        final String s = hex.trim();
        if (s.isEmpty() || (s.length() & 1) != 0) return null;
        final byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            final int hi = Character.digit(s.charAt(2 * i), 16);
            final int lo = Character.digit(s.charAt(2 * i + 1), 16);
            if (hi < 0 || lo < 0) return null;
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }
}
