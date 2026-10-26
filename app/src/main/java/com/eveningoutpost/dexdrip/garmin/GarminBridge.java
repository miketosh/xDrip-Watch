package com.eveningoutpost.dexdrip.garmin;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.eveningoutpost.dexdrip.models.BgReading;
import com.eveningoutpost.dexdrip.models.JoH;
import com.eveningoutpost.dexdrip.models.UserError;
import com.eveningoutpost.dexdrip.services.Ob1G5CollectionService;
import com.eveningoutpost.dexdrip.utilitymodels.Inevitable;
import com.garmin.android.connectiq.ConnectIQ;
import com.garmin.android.connectiq.IQApp;
import com.garmin.android.connectiq.IQDevice;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * xDrip-Watch: messages from xDrip to the G7 Scout Connect IQ watch app
 * (github.com/miketosh/garmin7-scout), via Garmin Connect on the phone.
 *
 * P1 sends a test ping; P2 sends the saved G7 key so the watch can
 * authenticate directly. See docs/XDRIP_WATCH_BRIDGE.md there. Pump status
 * follows the v1 contract in LOCAL_DATA_EXCHANGE_ARCHITECTURE.md, section 6.
 * The other way, the watch sends each reading of its direct G7 connection
 * ({@code glucose} v1, section 6.3); dev builds store it like any CGM reading,
 * so xDrip's screens, alerts and local web service (read by Garmin faces such
 * as Nightscout Watch 2) show it.
 */
public class GarminBridge {

    private static final String TAG = "GarminBridge";

    // garmin7-scout manifest.xml iq:application id.
    public static final String WATCH_APP_ID = "8d8a5d1f6b4a4f5fa8d3a7f0d2c9b641";

    public interface StatusListener {
        void onStatus(String line);
    }

    private static ConnectIQ connectIQ;
    private static boolean ready;
    private static final List<Runnable> whenReady = new ArrayList<>();
    private static final GarminWatchMessages.Deduper watchGlucoseSeen = new GarminWatchMessages.Deduper(64);
    /** Bridge test screen: the last watch message outcome. Never holds a glucose value. */
    public static volatile String lastWatchGlucose = "none yet";

    /** Settings → Smart Watch Features → Garmin watch: listen to the watch at all. */
    public static boolean watchGlucoseEnabled() {
        return GarminWatchPreferences.isEnabled();
    }

    /** Sends one test message to every known watch. */
    public static void sendTestMessage(Context context, StatusListener listener) {
        final Map<String, Object> message = new HashMap<>();
        message.put("t", "ping");
        message.put("v", 1);
        message.put("ts", System.currentTimeMillis() / 1000);
        send(context, message, listener);
    }

    /**
     * Sends the G7 key xDrip saved after pairing. The key is a secret: it is
     * never logged here, only whether one was available.
     */
    public static void sendKey(Context context, StatusListener listener) {
        final byte[] key = Ob1G5CollectionService.getKeksKeyForBridge();
        if (key == null) {
            report(listener, "No saved G7 key yet: wait for xDrip to take a reading, then retry");
            return;
        }
        final Map<String, Object> message = new HashMap<>();
        message.put("t", "key");
        message.put("v", 1);
        message.put("tx", Ob1G5CollectionService.getTransmitterID());
        message.put("key", JoH.bytesToHex(key));
        report(listener, "Sending saved G7 key (16 bytes) for transmitter " + Ob1G5CollectionService.getTransmitterID());
        send(context, message, listener);
    }

    /**
     * Sends a v1 pump_status message (GarminWatchMessages). Only its type and field count are
     * reported: the values are health data.
     */
    public static void sendPumpStatus(Context context, Map<String, Object> message, StatusListener listener) {
        if (!GarminWatchMessages.PUMP_STATUS.equals(message.get("t"))) return;
        report(listener, "Sending pump_status (" + message.size() + " fields)");
        send(context, message, listener);
    }

    /** Initializes the SDK if needed, then sends the message to every known watch. */
    private static void send(Context context, Map<String, Object> message, StatusListener listener) {
        withSdk(context, listener, () -> sendToKnownDevices(message, listener));
    }

    /**
     * Listens for messages from the watch app on every known watch (again on each call, so a
     * watch paired later is picked up). Accepted glucose readings are stored; nothing is sent back.
     */
    public static void startListening(Context context, StatusListener listener) {
        withSdk(context, listener, () -> listenToKnownDevices(listener));
    }

    /** Runs the action once the SDK is ready, initializing it first if needed. */
    private static synchronized void withSdk(Context context, StatusListener listener, Runnable action) {
        // The SDK creates Handlers: initialize and register on the main thread only
        // (a background caller crashed the app on 2026-10-26: "Can't create handler").
        if (Looper.myLooper() != Looper.getMainLooper()) {
            new Handler(Looper.getMainLooper()).post(() -> withSdk(context, listener, action));
            return;
        }
        final Context app = context.getApplicationContext();
        if (connectIQ == null) {
            connectIQ = ConnectIQ.getInstance(app, ConnectIQ.IQConnectType.WIRELESS);
        }
        if (ready) {
            action.run();
            return;
        }
        whenReady.add(action);
        if (whenReady.size() > 1) return; // initialization already under way
        report(listener, "Initializing Connect IQ SDK");
        connectIQ.initialize(app, true, new ConnectIQ.ConnectIQListener() {
            @Override
            public void onSdkReady() {
                final List<Runnable> actions;
                synchronized (GarminBridge.class) {
                    ready = true;
                    actions = new ArrayList<>(whenReady);
                    whenReady.clear();
                }
                report(listener, "SDK ready");
                for (final Runnable r : actions) r.run();
            }

            @Override
            public void onInitializeError(ConnectIQ.IQSdkErrorStatus status) {
                synchronized (GarminBridge.class) {
                    whenReady.clear();
                }
                report(listener, "SDK init error: " + status);
            }

            @Override
            public void onSdkShutDown() {
                ready = false;
                report(listener, "SDK shut down");
            }
        });
    }

    private static void sendToKnownDevices(Map<String, Object> message, StatusListener listener) {
        final List<IQDevice> devices;
        try {
            devices = connectIQ.getKnownDevices();
        } catch (Exception e) {
            report(listener, "getKnownDevices failed: " + e);
            return;
        }
        if (devices == null || devices.isEmpty()) {
            report(listener, "No known Garmin devices (is Garmin Connect paired?)");
            return;
        }
        final IQApp watchApp = new IQApp(WATCH_APP_ID);
        for (final IQDevice device : devices) {
            String status;
            try {
                status = String.valueOf(connectIQ.getDeviceStatus(device));
            } catch (Exception e) {
                status = "unknown (" + e + ")";
            }
            report(listener, "Device " + device.getFriendlyName() + " status=" + status);
            try {
                connectIQ.sendMessage(device, watchApp, message, (d, a, messageStatus) ->
                        report(listener, "Send " + message.get("t") + " to " + d.getFriendlyName() + ": " + messageStatus));
            } catch (Exception e) {
                report(listener, "sendMessage failed: " + e);
            }
        }
    }

    private static void listenToKnownDevices(StatusListener listener) {
        final List<IQDevice> devices;
        try {
            devices = connectIQ.getKnownDevices();
        } catch (Exception e) {
            report(listener, "getKnownDevices failed: " + e);
            return;
        }
        if (devices == null || devices.isEmpty()) {
            report(listener, "No known Garmin devices (is Garmin Connect paired?)");
            return;
        }
        final IQApp watchApp = new IQApp(WATCH_APP_ID);
        for (final IQDevice device : devices) {
            try {
                connectIQ.registerForAppEvents(device, watchApp, (d, a, messages, status) ->
                        onWatchMessages(messages, status, listener));
                report(listener, "Listening to " + device.getFriendlyName() + " for watch glucose");
            } catch (Exception e) {
                report(listener, "registerForAppEvents failed: " + e);
            }
        }
    }

    /** Contract 6.3: store a reading only if the watch marked it reliable, in range, dated sensibly and new. */
    private static void onWatchMessages(List<Object> messages, ConnectIQ.IQMessageStatus status, StatusListener listener) {
        if (status != ConnectIQ.IQMessageStatus.SUCCESS || messages == null) {
            report(listener, "Watch message status " + status);
            return;
        }
        for (final Object m : messages) {
            if (!(m instanceof Map)) continue;
            if (!GarminWatchMessages.GLUCOSE.equals(((Map<?, ?>) m).get("t"))) continue;
            final GarminWatchMessages.Parsed parsed = GarminWatchMessages.parseGlucose((Map<?, ?>) m, JoH.tsl());
            final GarminWatchMessages.Glucose g = parsed.glucose;
            if (g == null) {
                lastWatchGlucose = "rejected: " + parsed.reason + " " + JoH.hourMinuteString();
                report(listener, "Watch glucose rejected: " + parsed.reason);
                continue;
            }
            if (!GarminWatchPreferences.useWatchGlucose()) {
                lastWatchGlucose = "not used (setting off) seq " + g.sequence + " " + JoH.hourMinuteString();
                continue;
            }
            if (!watchGlucoseSeen.isNew(g)) continue;
            Inevitable.task("garmin-watch-glucose-" + g.sensor + g.sequence, 100, () -> {
                final BgReading bgr = BgReading.bgReadingInsertFromG5(g.mgdl, g.readingTimeSeconds * 1000L, "Garmin watch");
                lastWatchGlucose = (bgr != null ? "stored seq " : "not stored (no started sensor) seq ")
                        + g.sequence + " " + JoH.hourMinuteString();
                report(listener, "Watch glucose seq " + g.sequence + " from " + g.sensor
                        + (bgr != null ? ": stored" : ": not stored, start a sensor in xDrip first"));
            });
        }
    }

    private static void report(StatusListener listener, String line) {
        UserError.Log.d(TAG, line);
        if (listener != null) {
            listener.onStatus(line);
        }
    }
}
