package com.eveningoutpost.dexdrip.garmin;

import android.content.Context;

import com.eveningoutpost.dexdrip.models.UserError;
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
 * Phase P1 only sends a test message; see docs/XDRIP_WATCH_BRIDGE.md there.
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

    /** Initializes the SDK if needed, then sends one test message to every known watch. */
    public static synchronized void sendTestMessage(Context context, StatusListener listener) {
        final Context app = context.getApplicationContext();
        if (connectIQ == null) {
            connectIQ = ConnectIQ.getInstance(app, ConnectIQ.IQConnectType.WIRELESS);
        }
        if (ready) {
            sendToKnownDevices(listener);
            return;
        }
        report(listener, "Initializing Connect IQ SDK");
        connectIQ.initialize(app, true, new ConnectIQ.ConnectIQListener() {
            @Override
            public void onSdkReady() {
                ready = true;
                report(listener, "SDK ready");
                sendToKnownDevices(listener);
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

    private static void sendToKnownDevices(StatusListener listener) {
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
            final Map<String, Object> message = new HashMap<>();
            message.put("t", "ping");
            message.put("v", 1);
            message.put("ts", System.currentTimeMillis() / 1000);
            try {
                connectIQ.sendMessage(device, watchApp, message, (d, a, messageStatus) ->
                        report(listener, "Send to " + d.getFriendlyName() + ": " + messageStatus));
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
