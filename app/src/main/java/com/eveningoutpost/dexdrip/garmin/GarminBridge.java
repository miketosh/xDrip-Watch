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
 * Garmin watch support: messages between xDrip and the G7 Scout Connect IQ app
 * (github.com/miketosh/garmin7-scout) through Garmin Connect on the phone. xDrip sends a test
 * ping and, on request, the saved G7 key so the watch can log in to the sensor itself. The watch
 * sends each reading of its direct G7 connection ({@code glucose} v1); with the setting on, xDrip
 * stores it like any CGM reading, so its screens, alerts and local web service (read by Garmin
 * faces such as Nightscout Watch 2) show it. See Documentation/technical/GARMIN_WATCH_SUPPORT.md.
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
        report(listener, "Sending saved G7 key #" + keyPrint(key) + " (sensor " + Ob1G5CollectionService.getKeksKeyMacForBridge()
                + ") for transmitter " + Ob1G5CollectionService.getTransmitterID());
        send(context, message, listener);
    }

    /**
     * First 2 bytes of SHA-256(key) in hex, as the watch's Status screen shows on its
     * "Saved key" line: tells whether both hold the same key without showing it.
     */
    static String keyPrint(byte[] key) {
        try {
            final byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(key);
            return String.format("%02X%02X", digest[0] & 0xFF, digest[1] & 0xFF);
        } catch (Exception e) {
            return "?";
        }
    }

    /** Tells the watch which side connects to the G7 ({@code collector} v1). */
    public static void sendCollector(Context context, StatusListener listener) {
        send(context, GarminWatchMessages.collectorMessage(GarminWatchPreferences.isWatchCollector(),
                GarminWatchPreferences.collectorChangedAtSeconds()), listener);
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
        int connected = 0;
        for (final IQDevice device : devices) {
            IQDevice.IQDeviceStatus status = null;
            try {
                status = connectIQ.getDeviceStatus(device);
            } catch (Exception e) {
                report(listener, "Device " + device.getFriendlyName() + " status unknown: " + e);
            }
            if (status != IQDevice.IQDeviceStatus.CONNECTED) {
                report(listener, "Skipping " + device.getFriendlyName() + " (" + status + ")");
                continue;
            }
            connected++;
            sendWithRetry(device, watchApp, message, listener, 1);
        }
        if (connected == 0) {
            report(listener, "No Garmin watch connected to Garmin Connect");
        }
    }

    private static final int SEND_ATTEMPTS = 3;
    private static final long SEND_RETRY_MS = 2000;

    /**
     * The first message after the SDK starts often fails with FAILURE_DURING_TRANSFER and the
     * same message a moment later succeeds (owner's phone, 2026-10-26): retry a few times.
     */
    private static void sendWithRetry(IQDevice device, IQApp watchApp, Map<String, Object> message,
                                      StatusListener listener, int attempt) {
        try {
            connectIQ.sendMessage(device, watchApp, message, (d, a, messageStatus) -> {
                if (messageStatus == ConnectIQ.IQMessageStatus.SUCCESS) {
                    report(listener, "Sent " + message.get("t") + " to " + d.getFriendlyName());
                } else if (attempt < SEND_ATTEMPTS) {
                    report(listener, "Send " + message.get("t") + " try " + attempt + ": " + messageStatus + ", retrying");
                    new Handler(Looper.getMainLooper()).postDelayed(() ->
                            sendWithRetry(device, watchApp, message, listener, attempt + 1), SEND_RETRY_MS);
                } else {
                    report(listener, "Send " + message.get("t") + " failed after " + attempt + " tries: " + messageStatus
                            + " (is the G7 Scout app open on the watch?)");
                }
            });
        } catch (Exception e) {
            report(listener, "sendMessage failed: " + e);
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
        // A choice made here while the watch was away reaches it now.
        if (GarminWatchPreferences.collectorChangedAtSeconds() > 0) {
            sendToKnownDevices(GarminWatchMessages.collectorMessage(GarminWatchPreferences.isWatchCollector(),
                    GarminWatchPreferences.collectorChangedAtSeconds()), listener);
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
            if (GarminWatchMessages.COLLECTOR.equals(((Map<?, ?>) m).get("t"))) {
                onWatchCollector((Map<?, ?>) m, listener);
                continue;
            }
            if (!GarminWatchMessages.GLUCOSE.equals(((Map<?, ?>) m).get("t"))) continue;
            final GarminWatchMessages.Parsed parsed = GarminWatchMessages.parseGlucose((Map<?, ?>) m, JoH.tsl());
            final GarminWatchMessages.Glucose g = parsed.glucose;
            if (g == null) {
                lastWatchGlucose = "rejected: " + parsed.reason + " " + JoH.hourMinuteString();
                report(listener, "Watch glucose rejected: " + parsed.reason);
                continue;
            }
            if (!GarminWatchPreferences.useWatchGlucose()) {
                lastWatchGlucose = "not used (phone is the collector) seq " + g.sequence + " " + JoH.hourMinuteString();
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

    /** The watch's menu chose a collector: take it if newer, otherwise send ours back. */
    private static void onWatchCollector(Map<?, ?> m, StatusListener listener) {
        final GarminWatchMessages.Collector c = GarminWatchMessages.parseCollector(m);
        if (c == null) {
            report(listener, "Watch collector message rejected");
            return;
        }
        if (!GarminWatchPreferences.isEnabled()) return;
        if (!GarminWatchMessages.replacesPhoneChoice(c, GarminWatchPreferences.collectorChangedAtSeconds())) {
            if (c.watch != GarminWatchPreferences.isWatchCollector()) {
                sendToKnownDevices(GarminWatchMessages.collectorMessage(GarminWatchPreferences.isWatchCollector(),
                        GarminWatchPreferences.collectorChangedAtSeconds()), listener);
            }
            return;
        }
        GarminWatchPreferences.setWatchCollector(c.watch, c.changedAtSeconds, "set on the watch");
        GarminWatchPreferences.notifyCollector(c.watch, "set on the watch");
        report(listener, "Watch set the G7 collector to " + (c.watch ? "the watch" : "the phone"));
    }

    private static void report(StatusListener listener, String line) {
        UserError.Log.d(TAG, line);
        if (listener != null) {
            listener.onStatus(line);
        }
    }
}
