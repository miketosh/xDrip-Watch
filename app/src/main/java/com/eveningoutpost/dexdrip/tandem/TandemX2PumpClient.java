package com.eveningoutpost.dexdrip.tandem;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.Nullable;

import com.jwoglom.pumpx2.pump.TandemError;
import com.jwoglom.pumpx2.pump.bluetooth.PumpReadyState;
import com.jwoglom.pumpx2.pump.bluetooth.TandemBluetoothHandler;
import com.jwoglom.pumpx2.pump.bluetooth.TandemPump;
import com.jwoglom.pumpx2.pump.messages.Message;
import com.jwoglom.pumpx2.pump.messages.builders.JpakeAuthBuilder;
import com.jwoglom.pumpx2.pump.messages.request.currentStatus.ApiVersionRequest;
import com.jwoglom.pumpx2.pump.messages.request.currentStatus.ControlIQIOBRequest;
import com.jwoglom.pumpx2.pump.messages.request.currentStatus.CurrentBasalStatusRequest;
import com.jwoglom.pumpx2.pump.messages.request.currentStatus.CurrentBolusStatusRequest;
import com.jwoglom.pumpx2.pump.messages.request.currentStatus.NonControlIQIOBRequest;
import com.jwoglom.pumpx2.pump.messages.request.currentStatus.PumpVersionRequest;
import com.jwoglom.pumpx2.pump.messages.request.currentStatus.TimeSinceResetRequest;
import com.jwoglom.pumpx2.pump.messages.response.authentication.AbstractCentralChallengeResponse;
import com.jwoglom.pumpx2.pump.messages.response.authentication.AbstractPumpChallengeResponse;
import com.jwoglom.pumpx2.pump.messages.response.currentStatus.ControlIQIOBResponse;
import com.jwoglom.pumpx2.pump.messages.response.currentStatus.CurrentBasalStatusResponse;
import com.jwoglom.pumpx2.pump.messages.response.currentStatus.CurrentBolusStatusResponse;
import com.jwoglom.pumpx2.pump.messages.response.currentStatus.NonControlIQIOBResponse;
import com.jwoglom.pumpx2.pump.messages.response.qualifyingEvent.QualifyingEvent;
import com.welie.blessed.BluetoothPeripheral;
import com.welie.blessed.HciStatus;

import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Opt-in local BLE client for selected t:slim X2 status reads, backed by the community PumpX2
 * library. Prototype only: no application UI currently starts this client automatically.
 *
 * Safety boundary: permits authentication-package messages and a fixed set of read-only current
 * status requests. It intentionally does not expose bolus, basal/profile changes, suspend/resume,
 * or other therapy-control APIs. Do not call start() against an active pump before the connection
 * coexistence/recovery test has been reviewed and explicitly approved.
 */
public final class TandemX2PumpClient extends TandemPump {
    public enum ConnectionState {
        IDLE, SCANNING, CONNECTING, AUTHENTICATING, PAIRING_REQUIRED,
        CONNECTED, DISCONNECTED, ERROR, STOPPED
    }

    public interface Listener {
        void onConnectionStateChanged(ConnectionState state, String detail);
        void onSnapshot(TandemX2PumpSnapshot snapshot);
        void onQualifyingEvents(Set<QualifyingEvent> events);
    }

    private static final String AUTH_REQUEST_PACKAGE =
            "com.jwoglom.pumpx2.pump.messages.request.authentication";

    private final Context appContext;
    private final String targetMac;
    @Nullable private final Listener listener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Nullable private TandemBluetoothHandler bluetoothHandler;
    @Nullable private BluetoothPeripheral pendingPairingPeripheral;
    @Nullable private AbstractCentralChallengeResponse pendingCentralChallenge;
    private boolean started;

    @Nullable private TandemX2PumpSnapshot.IobValue controlIqIob;
    @Nullable private TandemX2PumpSnapshot.IobValue nonControlIqIob;
    @Nullable private TandemX2PumpSnapshot.BasalValue basal;
    @Nullable private TandemX2PumpSnapshot.CurrentBolusValue currentBolus;

    /** A specific, already-identified pump address is required to avoid attaching to a neighbor. */
    public TandemX2PumpClient(Context context, String targetMac, @Nullable Listener listener) {
        super(context, Optional.of(validateMac(targetMac)));
        this.appContext = context.getApplicationContext();
        this.targetMac = validateMac(targetMac);
        this.listener = listener;
    }

    /** Explicitly starts BLE discovery. Not invoked from xDrip startup or GarminBridge. */
    public synchronized void start() {
        if (started) return;
        started = true;
        notifyState(ConnectionState.SCANNING, "Scanning for the configured Tandem pump");
        try {
            // Null skips PumpX2's verbose debug tree. Do not call super.onPumpConnected(): that
            // implementation logs cached authentication material before sending default queries.
            bluetoothHandler = TandemBluetoothHandler.getInstance(appContext, this, null);
            bluetoothHandler.startScan();
        } catch (RuntimeException e) {
            started = false;
            notifyState(ConnectionState.ERROR,
                    "Unable to start Bluetooth (" + e.getClass().getSimpleName() + ")");
        }
    }

    /** Stops this PumpX2 handler. Does not unpair the phone or alter pump therapy. */
    public synchronized void stop() {
        if (started || bluetoothHandler != null) TandemBluetoothHandler.resetInstance();
        started = false;
        bluetoothHandler = null;
        pendingPairingPeripheral = null;
        pendingCentralChallenge = null;
        notifyState(ConnectionState.STOPPED, "Stopped by caller");
    }

    public synchronized boolean isPairingCodeRequired() {
        return pendingPairingPeripheral != null;
    }

    /**
     * Fresh pairing is intentionally disabled in this prototype. PumpX2 v1.9.3's inherited
     * TandemPump.pair() logs the supplied pairing code and persists it through PumpState. Do not
     * remove this guard until the upstream secret-logging and persistence behavior has been
     * reviewed and a safe pairing implementation is in place.
     */
    @Deprecated
    public synchronized boolean submitPairingCode(String pairingCode) {
        if (pendingPairingPeripheral != null) {
            notifyState(ConnectionState.ERROR,
                    "Fresh pairing is disabled pending pairing-code logging/storage hardening");
        }
        return false;
    }

    @Override
    public void onInitialPumpConnection(BluetoothPeripheral peripheral) {
        notifyState(ConnectionState.AUTHENTICATING, "Bluetooth connected; authentication pending");
        super.onInitialPumpConnection(peripheral);
    }

    @Override
    public boolean onPumpDiscovered(BluetoothPeripheral peripheral,
                                    @Nullable android.bluetooth.le.ScanResult scanResult,
                                    PumpReadyState readyState) {
        boolean accepted = super.onPumpDiscovered(peripheral, scanResult, readyState);
        if (accepted) notifyState(ConnectionState.CONNECTING, "Configured pump discovered");
        return accepted;
    }

    @Override
    public void onWaitingForPairingCode(BluetoothPeripheral peripheral,
                                        @Nullable AbstractCentralChallengeResponse centralChallenge) {
        synchronized (this) {
            pendingPairingPeripheral = peripheral;
            pendingCentralChallenge = centralChallenge;
        }
        notifyState(ConnectionState.PAIRING_REQUIRED,
                "Enter the pairing code shown by the pump in an explicit UI");
    }

    @Override
    public void onJpakeProgress(JpakeAuthBuilder.JpakeStep step) {
        notifyState(ConnectionState.AUTHENTICATING, "Pump authentication in progress");
    }

    @Override
    public void onInvalidPairingCode(BluetoothPeripheral peripheral,
                                     @Nullable AbstractPumpChallengeResponse response) {
        notifyState(ConnectionState.PAIRING_REQUIRED, "Pairing code rejected; retry from an explicit UI");
    }

    @Override
    public void onPumpConnected(BluetoothPeripheral peripheral) {
        synchronized (this) {
            pendingPairingPeripheral = null;
            pendingCentralChallenge = null;
        }
        notifyState(ConnectionState.CONNECTED, "Authenticated; requesting allowlisted status fields");
        // Intentionally do not call super.onPumpConnected() because it logs authentication material.
        sendCommand(peripheral, new ApiVersionRequest());
        sendCommand(peripheral, new PumpVersionRequest());
        sendCommand(peripheral, new TimeSinceResetRequest());
        sendCommand(peripheral, new CurrentBasalStatusRequest());
        sendCommand(peripheral, new CurrentBolusStatusRequest());
        sendCommand(peripheral, new ControlIQIOBRequest());
        sendCommand(peripheral, new NonControlIQIOBRequest());
    }

    @Override
    public void onReceiveMessage(BluetoothPeripheral peripheral, Message message) {
        final long receivedAt = System.currentTimeMillis();
        boolean changed = false;
        if (message instanceof ControlIQIOBResponse) {
            controlIqIob = TandemX2StatusMapper.fromControlIqIob(
                    (ControlIQIOBResponse) message, receivedAt);
            changed = true;
        } else if (message instanceof NonControlIQIOBResponse) {
            nonControlIqIob = TandemX2StatusMapper.fromNonControlIqIob(
                    (NonControlIQIOBResponse) message, receivedAt);
            changed = true;
        } else if (message instanceof CurrentBasalStatusResponse) {
            basal = TandemX2StatusMapper.fromBasal((CurrentBasalStatusResponse) message, receivedAt);
            changed = true;
        } else if (message instanceof CurrentBolusStatusResponse) {
            currentBolus = TandemX2StatusMapper.fromCurrentBolus(
                    (CurrentBolusStatusResponse) message, receivedAt);
            changed = true;
        }
        if (changed) publishSnapshot(receivedAt);
    }

    @Override
    public void onReceiveQualifyingEvent(BluetoothPeripheral peripheral, Set<QualifyingEvent> events) {
        if (listener == null || events == null) return;
        final Set<QualifyingEvent> copy = Collections.unmodifiableSet(new HashSet<>(events));
        mainHandler.post(() -> listener.onQualifyingEvents(copy));
    }

    @Override
    public boolean onPumpDisconnected(BluetoothPeripheral peripheral, HciStatus status) {
        notifyState(ConnectionState.DISCONNECTED, "Bluetooth disconnected (" + status.name() + ")");
        return true;
    }

    @Override
    public void onPumpCriticalError(BluetoothPeripheral peripheral, TandemError reason) {
        notifyState(ConnectionState.ERROR, reason == null ? "Pump protocol error" : reason.name());
    }

    /**
     * Runtime safety gate. The dependency contains therapy-changing messages; this wrapper admits
     * only authentication handshakes and fixed status reads. It does not accept arbitrary control
     * or history messages from callers.
     */
    @Override
    public synchronized void sendCommand(BluetoothPeripheral peripheral, Message message) {
        if (!isAllowedRequest(message)) {
            notifyState(ConnectionState.ERROR, "Blocked non-allowlisted pump request");
            return;
        }
        super.sendCommand(peripheral, message);
    }

    private static boolean isAllowedRequest(Message message) {
        if (message == null || message.getClass().getPackage() == null) return false;
        if (AUTH_REQUEST_PACKAGE.equals(message.getClass().getPackage().getName())) return true;
        return message instanceof ApiVersionRequest
                || message instanceof PumpVersionRequest
                || message instanceof TimeSinceResetRequest
                || message instanceof CurrentBasalStatusRequest
                || message instanceof CurrentBolusStatusRequest
                || message instanceof ControlIQIOBRequest
                || message instanceof NonControlIQIOBRequest;
    }

    private void publishSnapshot(long receivedAtMillis) {
        final TandemX2PumpSnapshot snapshot = new TandemX2PumpSnapshot(
                receivedAtMillis, controlIqIob, nonControlIqIob, basal, currentBolus);
        if (listener != null) mainHandler.post(() -> listener.onSnapshot(snapshot));
    }

    private void notifyState(ConnectionState state, String detail) {
        if (listener != null) mainHandler.post(() -> listener.onConnectionStateChanged(state, detail));
    }

    private static String validateMac(String mac) {
        if (mac == null || !mac.trim().matches("(?i)^[0-9a-f]{2}(:[0-9a-f]{2}){5}$")) {
            throw new IllegalArgumentException("A specific pump Bluetooth MAC address is required");
        }
        return mac.trim().toUpperCase(Locale.US);
    }

    public String getTargetMac() {
        return targetMac;
    }
}
