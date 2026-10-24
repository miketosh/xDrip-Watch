package com.eveningoutpost.dexdrip.garmin;

import android.content.Context;

import com.eveningoutpost.dexdrip.models.JoH;
import com.eveningoutpost.dexdrip.models.UserError;
import com.eveningoutpost.dexdrip.services.Ob1G5CollectionService;
import com.garmin.android.connectiq.ConnectIQ;
import com.garmin.android.connectiq.IQApp;
import com.garmin.android.connectiq.IQDevice;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * xDrip-Watch: messages from xDrip to the G7 Scout Connect IQ watch app
 * (github.com/miketosh/garmin7-scout), via Garmin Connect on the phone.
 *
 * P1 sends a test ping; P2 sends the saved G7 key so the watch can
 * authenticate directly. See docs/XDRIP_WATCH_BRIDGE.md there.
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

    /** Initializes the SDK if needed, then sends the message to every known watch. */
    private static synchronized void send(Context context, Map<String, Object> message, StatusListener listener) {
        final Context app = context.getApplicationContext();
        if (connectIQ == null) {
            connectIQ = ConnectIQ.getInstance(app, ConnectIQ.IQConnectType.WIRELESS);
        }
        if (ready) {
            sendToKnownDevices(message, listener);
            return;
        }
        report(listener, "Initializing Connect IQ SDK");
        connectIQ.initialize(app, true, new ConnectIQ.ConnectIQListener() {
            @Override
            public void onSdkReady() {
                ready = true;
                report(listener, "SDK ready");
                sendToKnownDevices(message, listener);
            }

            @Override
            public void onInitializeError(ConnectIQ.IQSdkErrorStatus status) {
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

    private static void report(StatusListener listener, String line) {
        UserError.Log.d(TAG, line);
        if (listener != null) {
            listener.onStatus(line);
        }
    }
}
