package com.eveningoutpost.dexdrip.tandem;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.Nullable;

import com.jwoglom.pumpx2.pump.PumpState;
import com.jwoglom.pumpx2.pump.TandemError;
import com.jwoglom.pumpx2.pump.bluetooth.PumpReadyState;
import com.jwoglom.pumpx2.pump.bluetooth.TandemBluetoothHandler;
import com.jwoglom.pumpx2.pump.bluetooth.TandemPump;
import com.jwoglom.pumpx2.pump.messages.Message;
import com.jwoglom.pumpx2.pump.messages.builders.JpakeAuthBuilder;
import com.jwoglom.pumpx2.pump.messages.builders.PumpChallengeRequestBuilder;
import com.jwoglom.pumpx2.pump.messages.models.KnownDeviceModel;
import com.jwoglom.pumpx2.pump.messages.models.PairingCodeType;
import com.jwoglom.pumpx2.pump.messages.request.currentStatus.ApiVersionRequest;
import com.jwoglom.pumpx2.pump.messages.request.currentStatus.ControlIQIOBRequest;
import com.jwoglom.pumpx2.pump.messages.request.currentStatus.CurrentBasalStatusRequest;
import com.jwoglom.pumpx2.pump.messages.request.currentStatus.CurrentBolusStatusRequest;
import com.jwoglom.pumpx2.pump.messages.request.currentStatus.NonControlIQIOBRequest;
import com.jwoglom.pumpx2.pump.messages.request.currentStatus.PumpVersionRequest;
import com.jwoglom.pumpx2.pump.messages.request.currentStatus.TimeSinceResetRequest;
import com.jwoglom.pumpx2.pump.messages.request.control.InitiateBolusRequest;
import com.jwoglom.pumpx2.pump.messages.response.control.BolusPermissionReleaseResponse;
import com.jwoglom.pumpx2.pump.messages.response.control.BolusPermissionResponse;
import com.jwoglom.pumpx2.pump.messages.response.control.CancelBolusResponse;
import com.jwoglom.pumpx2.pump.messages.response.control.InitiateBolusResponse;
import com.jwoglom.pumpx2.pump.messages.response.currentStatus.BolusCalcDataSnapshotResponse;
import com.eveningoutpost.dexdrip.BuildConfig;
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
 * Safety boundary: {@link TandemX2RequestPolicy} decides every outgoing message. Listen-only
 * (the default) sends none; the other modes send authentication messages (own pairing only) and
 * a fixed set of read-only status requests. The one therapy exception is the owner-approved
 * experimental bolus, which only {@link TandemX2BolusSession} can send, through
 * {@link #sendBolusStep}, in own-pairing mode and non-release builds. No basal/profile change,
 * suspend/resume or other therapy-control API is reachable. Do not call start() against an
 * active pump unless the owner is supervising the test.
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

    private final Context appContext;
    private final String targetMac;
    private final TandemX2ConnectionMode mode;
    @Nullable private final Listener listener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Nullable private TandemBluetoothHandler bluetoothHandler;
    @Nullable private BluetoothPeripheral pendingPairingPeripheral;
    @Nullable private AbstractCentralChallengeResponse pendingCentralChallenge;
    @Nullable private KnownDeviceModel deviceModel;
    @Nullable private BluetoothPeripheral connectedPeripheral;
    @Nullable private TandemX2BolusSession bolusSession;
    private boolean started;

    @Nullable private TandemX2PumpSnapshot.IobValue controlIqIob;
    @Nullable private TandemX2PumpSnapshot.IobValue nonControlIqIob;
    @Nullable private TandemX2PumpSnapshot.BasalValue basal;
    @Nullable private TandemX2PumpSnapshot.CurrentBolusValue currentBolus;

    /** A specific, already-identified pump address is required to avoid attaching to a neighbor. */
    public TandemX2PumpClient(Context context, String targetMac,
                              @Nullable TandemX2ConnectionMode mode, @Nullable Listener listener) {
        super(context, Optional.of(validateMac(targetMac)));
        this.appContext = context.getApplicationContext();
        this.targetMac = validateMac(targetMac);
        this.mode = mode == null ? TandemX2ConnectionMode.LISTEN_ONLY : mode;
        this.listener = listener;
    }

    /** Explicitly starts BLE discovery. Not invoked from xDrip startup or GarminBridge. */
    public synchronized void start() {
        if (started) return;
        if (!TandemX2RequestPolicy.isPumpX2LoggingSilent()) {
            notifyState(ConnectionState.ERROR,
                    "A Timber log tree is planted; PumpX2 would log pump data and secrets");
            return;
        }
        started = true;
        applyModeToPumpState();
        notifyState(ConnectionState.SCANNING, "Scanning for the configured Tandem pump (" + mode + ")");
        try {
            // A null tree keeps PumpX2 from planting its debug tree (checked above).
            bluetoothHandler = TandemBluetoothHandler.getInstance(appContext, this, null);
            bluetoothHandler.startScan();
        } catch (RuntimeException e) {
            started = false;
            notifyState(ConnectionState.ERROR,
                    "Unable to start Bluetooth (" + e.getClass().getSimpleName() + ")");
        }
    }

    /**
     * PumpState holds these as process-wide statics, so set every one for this mode rather than
     * relying on defaults left by an earlier session.
     */
    private void applyModeToPumpState() {
        final boolean shared = mode != TandemX2ConnectionMode.OWN_PAIRING;
        PumpState.tconnectAppConnectionSharing = shared;
        PumpState.relyOnConnectionSharingForAuthentication = shared;
        PumpState.sendSharedConnectionResponseMessages = shared;
        PumpState.onlySnoopBluetooth = mode == TandemX2ConnectionMode.LISTEN_ONLY;
        PumpState.tconnectAppAlreadyAuthenticated = false;
        PumpState.tconnectAppConnectionSharingIgnoreInitialFailingWrite = false;
    }

    /** Stops this PumpX2 handler. Does not unpair the phone or alter pump therapy. */
    public synchronized void stop() {
        if (started || bluetoothHandler != null) TandemBluetoothHandler.resetInstance();
        started = false;
        bluetoothHandler = null;
        connectedPeripheral = null;
        pendingPairingPeripheral = null;
        pendingCentralChallenge = null;
        notifyState(ConnectionState.STOPPED, "Stopped by caller");
    }

    public TandemX2ConnectionMode getMode() {
        return mode;
    }

    public synchronized boolean isPairingCodeRequired() {
        return pendingPairingPeripheral != null;
    }

    /**
     * Pairs with the code shown on the pump (Bluetooth settings, Pair Device). Own-pairing mode
     * only; returns false if no pairing is pending or the code is malformed.
     */
    public synchronized boolean submitPairingCode(String pairingCode) {
        if (mode != TandemX2ConnectionMode.OWN_PAIRING || pendingPairingPeripheral == null) {
            return false;
        }
        if (TandemX2RequestPolicy.normalizePairingCode(pairingCode) == null) {
            notifyState(ConnectionState.PAIRING_REQUIRED, "Pairing code must be 6 digits or 16 characters");
            return false;
        }
        pair(pendingPairingPeripheral, pendingCentralChallenge, pairingCode);
        return true;
    }

    /**
     * The steps of PumpX2 v1.9.3's TandemPump.pair() without its Timber lines, which print the
     * pairing code and the J-PAKE derived secret. Keep in step with upstream when PumpX2 is
     * upgraded.
     */
    @Override
    public void pair(BluetoothPeripheral peripheral,
                     @Nullable AbstractCentralChallengeResponse centralChallenge, String pairingCode) {
        if (mode != TandemX2ConnectionMode.OWN_PAIRING) {
            notifyState(ConnectionState.ERROR, "Pairing is only used in own-pairing mode");
            return;
        }
        final String code = TandemX2RequestPolicy.normalizePairingCode(pairingCode);
        if (code == null) {
            onInvalidPairingCode(peripheral, null);
            return;
        }
        if (PumpState.pairingCodeType == PairingCodeType.SHORT_6CHAR
                || deviceModel == KnownDeviceModel.MOBI || code.length() == 6) {
            PumpState.pairingCodeType = PairingCodeType.SHORT_6CHAR;
            final String savedSecretHex = PumpState.getJpakeDerivedSecret(appContext);
            PumpState.setJpakeServerNonce(appContext, "");
            PumpState.setPairingCode(appContext, code);
            JpakeAuthBuilder.clearInstance();
            if (savedSecretHex == null || savedSecretHex.trim().isEmpty()) {
                sendCommand(peripheral, JpakeAuthBuilder.initializeWithPairingCode(code).nextRequest());
                return;
            }
            final byte[] savedSecret = TandemX2RequestPolicy.hexToBytes(savedSecretHex);
            if (savedSecret == null) {
                PumpState.setJpakeDerivedSecret(appContext, "");
                onInvalidPairingCode(peripheral, null);
                return;
            }
            sendCommand(peripheral, JpakeAuthBuilder.initializeWithDerivedSecret(code, savedSecret).nextRequest());
        } else {
            try {
                PumpState.setPairingCode(appContext, code);
                sendCommand(peripheral, PumpChallengeRequestBuilder.create(centralChallenge, code));
            } catch (PumpChallengeRequestBuilder.InvalidPairingCodeFormat e) {
                onInvalidPairingCode(peripheral, null);
            }
        }
    }

    @Override
    public void onPumpModel(BluetoothPeripheral peripheral, KnownDeviceModel model) {
        super.onPumpModel(peripheral, model);
        deviceModel = model;
    }

    @Override
    public void onInitialPumpConnection(BluetoothPeripheral peripheral) {
        notifyState(ConnectionState.AUTHENTICATING, mode == TandemX2ConnectionMode.OWN_PAIRING
                ? "Bluetooth connected; authentication pending"
                : "Bluetooth connected; waiting for the Tandem app's traffic");
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
        if (mode != TandemX2ConnectionMode.OWN_PAIRING) {
            notifyState(ConnectionState.ERROR,
                    "Pump asked for pairing; is the Tandem app connected? (" + mode + ")");
            return;
        }
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
            connectedPeripheral = peripheral;
        }
        // Intentionally do not call super.onPumpConnected() because it logs authentication material.
        if (mode == TandemX2ConnectionMode.LISTEN_ONLY) {
            notifyState(ConnectionState.CONNECTED, "Listening to the pump's replies to the Tandem app");
            return;
        }
        notifyState(ConnectionState.CONNECTED, "Authenticated; requesting allowlisted status fields");
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
        routeToBolusSession(message, receivedAt);
        if (changed) publishSnapshot(receivedAt);
    }

    private void routeToBolusSession(Message message, long receivedAt) {
        final TandemX2BolusSession session = bolusSession;
        if (session == null) return;
        if (message instanceof BolusCalcDataSnapshotResponse) {
            session.onSnapshot((BolusCalcDataSnapshotResponse) message, receivedAt);
        } else if (message instanceof BolusPermissionResponse) {
            session.onPermission((BolusPermissionResponse) message, receivedAt);
        } else if (message instanceof InitiateBolusResponse) {
            session.onInitiate((InitiateBolusResponse) message);
        } else if (message instanceof CancelBolusResponse) {
            session.onCancel((CancelBolusResponse) message);
        } else if (message instanceof BolusPermissionReleaseResponse) {
            session.onRelease((BolusPermissionReleaseResponse) message);
        } else if (message instanceof CurrentBolusStatusResponse) {
            session.onCurrentBolus((CurrentBolusStatusResponse) message);
        }
    }

    /** The experimental bolus session that receives bolus responses; see AGENTS.md gates. */
    public synchronized void setBolusSession(@Nullable TandemX2BolusSession session) {
        bolusSession = session;
    }

    public static boolean isReleaseBuild() {
        return "release".equals(BuildConfig.BUILD_TYPE);
    }

    /**
     * The only path for bolus messages, used by {@link TandemX2BolusSession}. Separate from
     * {@link #sendCommand}, whose allowlist never admits them. Re-checks the build, the mode and
     * the message type, and enables PumpX2's insulin-delivery switch only for the initiate
     * (PumpX2 has no way to switch it off again for the rest of the process).
     */
    public synchronized boolean sendBolusStep(Message message) {
        final String blocked = isReleaseBuild() ? "release build"
                : mode != TandemX2ConnectionMode.OWN_PAIRING ? mode + " mode"
                : !TandemX2BolusPolicy.isBolusStep(message) ? "not a bolus step"
                : connectedPeripheral == null ? "pump not connected" : null;
        if (blocked != null) {
            notifyState(ConnectionState.ERROR, "Blocked bolus step: " + blocked);
            return false;
        }
        if (message instanceof InitiateBolusRequest) {
            enableActionsAffectingInsulinDelivery();
        }
        super.sendCommand(connectedPeripheral, message);
        return true;
    }

    /** Status read used while a bolus is delivering; goes through the normal allowlist. */
    public synchronized void requestCurrentBolusStatus() {
        if (connectedPeripheral != null) sendCommand(connectedPeripheral, new CurrentBolusStatusRequest());
    }

    @Override
    public void onReceiveQualifyingEvent(BluetoothPeripheral peripheral, Set<QualifyingEvent> events) {
        if (listener == null || events == null) return;
        final Set<QualifyingEvent> copy = Collections.unmodifiableSet(new HashSet<>(events));
        mainHandler.post(() -> listener.onQualifyingEvents(copy));
    }

    @Override
    public boolean onPumpDisconnected(BluetoothPeripheral peripheral, HciStatus status) {
        synchronized (this) {
            connectedPeripheral = null;
        }
        notifyState(ConnectionState.DISCONNECTED, "Bluetooth disconnected (" + status.name() + ")");
        return true;
    }

    @Override
    public void onPumpCriticalError(BluetoothPeripheral peripheral, TandemError reason) {
        notifyState(ConnectionState.ERROR, reason == null ? "Pump protocol error" : reason.name());
    }

    /**
     * Runtime safety gate: every outgoing message, including those PumpX2's handler sends on its
     * own, goes through {@link TandemX2RequestPolicy#isAllowed}.
     */
    @Override
    public synchronized void sendCommand(BluetoothPeripheral peripheral, Message message) {
        if (!TandemX2RequestPolicy.isAllowed(mode, message)) {
            notifyState(ConnectionState.ERROR, "Blocked request in " + mode + " mode");
            return;
        }
        super.sendCommand(peripheral, message);
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
