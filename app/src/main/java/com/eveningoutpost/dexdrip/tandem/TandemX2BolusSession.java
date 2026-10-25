package com.eveningoutpost.dexdrip.tandem;

import androidx.annotation.Nullable;

import com.jwoglom.pumpx2.pump.messages.Message;
import com.jwoglom.pumpx2.pump.messages.request.control.BolusPermissionReleaseRequest;
import com.jwoglom.pumpx2.pump.messages.request.control.BolusPermissionRequest;
import com.jwoglom.pumpx2.pump.messages.request.control.CancelBolusRequest;
import com.jwoglom.pumpx2.pump.messages.request.control.InitiateBolusRequest;
import com.jwoglom.pumpx2.pump.messages.request.currentStatus.BolusCalcDataSnapshotRequest;
import com.jwoglom.pumpx2.pump.messages.response.control.BolusPermissionReleaseResponse;
import com.jwoglom.pumpx2.pump.messages.response.control.BolusPermissionResponse;
import com.jwoglom.pumpx2.pump.messages.response.control.CancelBolusResponse;
import com.jwoglom.pumpx2.pump.messages.response.control.InitiateBolusResponse;
import com.jwoglom.pumpx2.pump.messages.response.currentStatus.BolusCalcDataSnapshotResponse;
import com.jwoglom.pumpx2.pump.messages.response.currentStatus.CurrentBolusStatusResponse;

/**
 * One experimental phone bolus at a time: read the pump's bolus limits, take the pump's bolus
 * permission, wait for the user's separate confirmation, initiate, follow until done.
 *
 * Safety rules: only {@link #request} and {@link #confirm} (explicit user actions) move a bolus
 * forward; nothing is ever re-sent; an unanswered initiate leaves the session UNKNOWN, which
 * blocks new requests until the user has checked the pump and called {@link #acknowledge}.
 * Pure Java (no Android types, time passed in) so every rule is unit-tested.
 */
public final class TandemX2BolusSession {

    public interface Gates {
        boolean isReleaseBuild();
        boolean isEnabled();
        TandemX2ConnectionMode mode();
        long capMilliunits();
    }

    /** Sends one bolus step to the pump; false if it was not sent. */
    public interface Sender {
        boolean send(Message message);
    }

    public interface Listener {
        void onBolusState(State state, String detail);
    }

    public enum State {
        IDLE, CHECKING_PUMP, AWAITING_PERMISSION, READY_TO_CONFIRM, SENDING, DELIVERING,
        CANCELLING, DONE, REFUSED, UNKNOWN
    }

    static final long RESPONSE_TIMEOUT_MS = 10_000;
    static final long CONFIRM_WINDOW_MS = 30_000;

    private final Gates gates;
    private final Sender sender;
    @Nullable private final Listener listener;

    private State state = State.IDLE;
    private long milliunits;
    private int bolusId;
    private long deadlineMs;

    public TandemX2BolusSession(Gates gates, Sender sender, @Nullable Listener listener) {
        this.gates = gates;
        this.sender = sender;
        this.listener = listener;
    }

    public synchronized State getState() {
        return state;
    }

    public synchronized long getMilliunits() {
        return milliunits;
    }

    /** User action: typed amount. Reads the pump's limits first; delivers nothing yet. */
    public synchronized TandemX2BolusPolicy.Refusal request(@Nullable Long amountMilliunits, long nowMs) {
        if (state != State.IDLE && state != State.DONE && state != State.REFUSED) {
            set(state, state == State.UNKNOWN
                    ? "Check the pump's bolus history first, then acknowledge"
                    : "A bolus is already in progress");
            return TandemX2BolusPolicy.Refusal.BUSY;
        }
        final TandemX2BolusPolicy.Refusal refusal = TandemX2BolusPolicy.check(gates.isReleaseBuild(),
                gates.isEnabled(), gates.mode(), amountMilliunits, gates.capMilliunits(), null);
        if (refusal != TandemX2BolusPolicy.Refusal.NONE) {
            set(State.REFUSED, refusal.text);
            return refusal;
        }
        milliunits = amountMilliunits;
        bolusId = 0;
        if (!sendStep(new BolusCalcDataSnapshotRequest())) return TandemX2BolusPolicy.Refusal.NOT_CONNECTED;
        deadlineMs = nowMs + RESPONSE_TIMEOUT_MS;
        set(State.CHECKING_PUMP, "Reading the pump's bolus limits for "
                + TandemX2BolusPolicy.formatUnits(milliunits) + " U");
        return TandemX2BolusPolicy.Refusal.NONE;
    }

    public synchronized void onSnapshot(BolusCalcDataSnapshotResponse response, long nowMs) {
        if (state != State.CHECKING_PUMP || response == null) return;
        final TandemX2BolusPolicy.Refusal refusal = TandemX2BolusPolicy.check(gates.isReleaseBuild(),
                gates.isEnabled(), gates.mode(), milliunits, gates.capMilliunits(),
                (long) response.getMaxBolusAmount());
        if (refusal != TandemX2BolusPolicy.Refusal.NONE) {
            set(State.REFUSED, refusal.text);
            return;
        }
        if (!sendStep(new BolusPermissionRequest())) return;
        deadlineMs = nowMs + RESPONSE_TIMEOUT_MS;
        set(State.AWAITING_PERMISSION, "Asking the pump for bolus permission");
    }

    public synchronized void onPermission(BolusPermissionResponse response, long nowMs) {
        if (response == null) return;
        if (state != State.AWAITING_PERMISSION) {
            // Late grant (after a timeout or cancel): give the permission back.
            if (response.isPermissionGranted() && response.getBolusId() > 0) {
                sender.send(new BolusPermissionReleaseRequest(response.getBolusId()));
            }
            return;
        }
        if (!response.isPermissionGranted() || response.getBolusId() <= 0) {
            set(State.REFUSED, "Pump refused bolus permission (" + response.getNackReason() + ")");
            return;
        }
        bolusId = response.getBolusId();
        deadlineMs = nowMs + CONFIRM_WINDOW_MS;
        set(State.READY_TO_CONFIRM, "Confirm " + TandemX2BolusPolicy.formatUnits(milliunits)
                + " U within " + (CONFIRM_WINDOW_MS / 1000) + " s");
    }

    /** User action: the separate confirmation. Sends the one InitiateBolusRequest. */
    public synchronized boolean confirm(long nowMs) {
        if (state != State.READY_TO_CONFIRM) return false;
        if (nowMs > deadlineMs) {
            releasePermission("Confirmation window expired; nothing delivered");
            return false;
        }
        // Re-check the gates: settings may have changed while the dialog was open.
        final TandemX2BolusPolicy.Refusal refusal = TandemX2BolusPolicy.check(gates.isReleaseBuild(),
                gates.isEnabled(), gates.mode(), milliunits, gates.capMilliunits(), null);
        if (refusal != TandemX2BolusPolicy.Refusal.NONE) {
            releasePermission(refusal.text);
            return false;
        }
        final InitiateBolusRequest initiate = new InitiateBolusRequest(milliunits, bolusId,
                TandemX2BolusPolicy.UNITS_ONLY_BOLUS_TYPE, 0, 0, 0, 0, 0);
        // State first: a second confirm() must not send a second initiate.
        state = State.SENDING;
        deadlineMs = nowMs + RESPONSE_TIMEOUT_MS;
        if (!sender.send(initiate)) {
            set(State.UNKNOWN, "Initiate could not be sent; check the pump before anything else");
            return false;
        }
        set(State.SENDING, "Bolus " + TandemX2BolusPolicy.formatUnits(milliunits) + " U sent to the pump");
        return true;
    }

    public synchronized void onInitiate(InitiateBolusResponse response) {
        if (response == null || (state != State.SENDING && state != State.UNKNOWN)) return;
        if (response.getBolusId() != bolusId) return;
        if (response.wasBolusInitiated()) {
            set(State.DELIVERING, "Pump is delivering " + TandemX2BolusPolicy.formatUnits(milliunits) + " U");
        } else {
            set(State.REFUSED, "Pump did not start the bolus (" + response.getStatusType() + ")");
        }
    }

    /** Pump's current-bolus status; bolus id 0 means no bolus is running. */
    public synchronized void onCurrentBolus(CurrentBolusStatusResponse response) {
        if (response == null || state != State.DELIVERING) return;
        if (response.getBolusId() == 0) {
            set(State.DONE, "Bolus finished; confirm the amount in the pump's history");
        }
    }

    /** User action. Before initiate: releases the permission. After: asks the pump to cancel. */
    public synchronized void cancel(long nowMs) {
        switch (state) {
            case CHECKING_PUMP:
            case AWAITING_PERMISSION:
                set(State.IDLE, "Cancelled before the pump granted permission");
                return;
            case READY_TO_CONFIRM:
                releasePermission("Cancelled; nothing delivered");
                return;
            case SENDING:
            case DELIVERING:
            case UNKNOWN:
                if (bolusId > 0 && sendStep(new CancelBolusRequest(bolusId))) {
                    deadlineMs = nowMs + RESPONSE_TIMEOUT_MS;
                    set(State.CANCELLING, "Asked the pump to cancel bolus " + bolusId);
                }
                return;
            default:
        }
    }

    public synchronized void onCancel(CancelBolusResponse response) {
        if (response == null || state != State.CANCELLING || response.getBolusId() != bolusId) return;
        set(State.DONE, response.wasCancelled()
                ? "Pump cancelled the bolus; check its history for the amount delivered"
                : "Pump did not cancel (" + response.getReason() + "); check its history");
    }

    public synchronized void onRelease(BolusPermissionReleaseResponse response) {
        // Informational; the session has already moved on.
    }

    /** User action after an UNKNOWN outcome, once the pump's history has been checked. */
    public synchronized void acknowledge() {
        if (state == State.UNKNOWN) set(State.IDLE, "Acknowledged");
    }

    /** Called about once a second. Timeouts never re-send; they only change the state. */
    public synchronized void tick(long nowMs) {
        if (nowMs <= deadlineMs) return;
        switch (state) {
            case CHECKING_PUMP:
                set(State.REFUSED, "No reply from the pump; nothing delivered");
                return;
            case AWAITING_PERMISSION:
                set(State.REFUSED, "No permission reply; nothing delivered");
                return;
            case READY_TO_CONFIRM:
                releasePermission("Confirmation window expired; nothing delivered");
                return;
            case SENDING:
                set(State.UNKNOWN, "No reply to the bolus; it may be running. Check the pump; nothing was re-sent");
                return;
            case CANCELLING:
                set(State.UNKNOWN, "No reply to the cancel; check the pump");
                return;
            default:
        }
    }

    private void releasePermission(String detail) {
        if (bolusId > 0) sender.send(new BolusPermissionReleaseRequest(bolusId));
        set(State.REFUSED, detail);
    }

    private boolean sendStep(Message message) {
        if (sender.send(message)) return true;
        set(State.REFUSED, "Could not send to the pump (not connected in own-pairing mode?)");
        return false;
    }

    private void set(State newState, String detail) {
        state = newState;
        if (listener != null) listener.onBolusState(newState, detail);
    }
}
