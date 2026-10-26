package com.eveningoutpost.dexdrip.garmin;

import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.Intent;
import android.preference.Preference;
import android.preference.PreferenceFragment;

import com.eveningoutpost.dexdrip.Home;
import com.eveningoutpost.dexdrip.R;
import com.eveningoutpost.dexdrip.models.BgReading;
import com.eveningoutpost.dexdrip.models.JoH;
import com.eveningoutpost.dexdrip.models.UserError;
import com.eveningoutpost.dexdrip.utilitymodels.CollectionServiceStarter;
import com.eveningoutpost.dexdrip.utilitymodels.Constants;
import com.eveningoutpost.dexdrip.utilitymodels.Inevitable;
import com.eveningoutpost.dexdrip.utilitymodels.PersistentStore;
import com.eveningoutpost.dexdrip.utilitymodels.Pref;
import com.eveningoutpost.dexdrip.xdrip;

/**
 * Settings → Smart Watch Features → Garmin watch (G7 Scout). Everything here is off by default;
 * the screen is in pref_advanced_settings.xml and attached with one line in Preferences.
 */
public final class GarminWatchPreferences {
    private GarminWatchPreferences() {}

    private static final String TAG = "GarminWatch";

    /** Talk to the G7 Scout Connect IQ app through Garmin Connect. */
    public static final String PREF_ENABLED = "garmin_watch_enabled";
    /**
     * The watch, not this phone, connects to the G7 (like "Force Wear Collection Service"): the
     * phone's G7 collector stays off and the watch's readings are stored. Only one client may use
     * the sensor's app slot. The watch's menu can switch it too; the newer change wins.
     */
    public static final String PREF_WATCH_COLLECTOR = "garmin_watch_collector";
    /** While the watch collects: switch back to this phone after missed readings. */
    public static final String PREF_FALLBACK = "garmin_watch_fallback";
    public static final String PREF_FALLBACK_MINUTES = "garmin_watch_fallback_minutes";
    /** When the collector choice last changed (epoch seconds), here or on the watch. */
    private static final String STORE_COLLECTOR_CHANGED = "garmin_watch_collector_changed";
    private static final int NOTIFICATION_ID = 7713;
    /** Action: send this phone's saved G7 key to the watch. */
    public static final String PREF_SEND_KEY = "garmin_watch_send_key";

    public static boolean isEnabled() {
        return Pref.getBooleanDefaultFalse(PREF_ENABLED);
    }

    public static boolean isWatchCollector() {
        return isEnabled() && Pref.getBooleanDefaultFalse(PREF_WATCH_COLLECTOR);
    }

    /** Watch readings are stored while the watch is the collector. */
    public static boolean useWatchGlucose() {
        return isWatchCollector();
    }

    public static long collectorChangedAtSeconds() {
        return PersistentStore.getLong(STORE_COLLECTOR_CHANGED);
    }

    /** Applies a choice made on the watch or by the fallback, then starts or stops the phone collector. */
    static void setWatchCollector(boolean watch, long changedAtSeconds, String why) {
        Pref.setBoolean(PREF_WATCH_COLLECTOR, watch);
        PersistentStore.setLong(STORE_COLLECTOR_CHANGED, changedAtSeconds);
        UserError.Log.uel(TAG, "G7 collector: " + (watch ? "Garmin watch" : "phone") + " (" + why + ")");
        CollectionServiceStarter.restartCollectionServiceBackground();
    }

    static void notifyCollector(boolean watch, String why) {
        try {
            final PendingIntent home = PendingIntent.getActivity(xdrip.getAppContext(), 0,
                    new Intent(xdrip.getAppContext(), Home.class),
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            JoH.showNotification(watch ? "Garmin watch collects" : "Phone collects",
                    "G7 collector: " + (watch ? "Garmin watch" : "this phone") + " (" + why + ")",
                    home, NOTIFICATION_ID, false, false, true);
        } catch (Exception e) {
            JoH.static_toast_long("G7 collector: " + (watch ? "Garmin watch" : "this phone"));
        }
    }

    /** MissedReadingService: like "Disable Force Wear after missed readings". */
    public static void checkFallback() {
        if (!isWatchCollector() || !Pref.getBooleanDefaultFalse(PREF_FALLBACK)) return;
        final int minutes = Math.max(10, Pref.getStringToInt(PREF_FALLBACK_MINUTES, 30));
        if (BgReading.getTimeSinceLastReading() < minutes * Constants.MINUTE_IN_MS) return;
        final String why = "no reading for " + minutes + " min";
        setWatchCollector(false, JoH.tsl() / 1000, why);
        notifyCollector(false, why);
        GarminBridge.sendCollector(xdrip.getAppContext(), null);
    }

    /** Hooks the screen's actions; never throws into the settings screen. */
    public static void attach(PreferenceFragment fragment) {
        try {
            final Preference enabled = fragment.findPreference(PREF_ENABLED);
            if (enabled != null) {
                enabled.setOnPreferenceChangeListener((preference, newValue) -> {
                    if (Boolean.TRUE.equals(newValue)) {
                        GarminBridge.startListening(preference.getContext(), null);
                    }
                    return true;
                });
            }
            final Preference collector = fragment.findPreference(PREF_WATCH_COLLECTOR);
            if (collector != null) {
                collector.setOnPreferenceChangeListener((preference, newValue) -> {
                    PersistentStore.setLong(STORE_COLLECTOR_CHANGED, JoH.tsl() / 1000);
                    // After the switch is saved: stop or start the phone collector, tell the watch.
                    Inevitable.task("garmin-collector-changed", 300, () -> {
                        CollectionServiceStarter.restartCollectionServiceBackground();
                        GarminBridge.sendCollector(xdrip.getAppContext(), line -> {
                            if (line.startsWith("Sent") || line.contains("failed") || line.startsWith("No ")) {
                                JoH.static_toast_long(line);
                            }
                        });
                    });
                    return true;
                });
            }
            final Preference sendKey = fragment.findPreference(PREF_SEND_KEY);
            if (sendKey != null) {
                sendKey.setOnPreferenceClickListener(preference -> {
                    // The key is a secret: confirm before it leaves the phone.
                    new AlertDialog.Builder(preference.getContext())
                            .setTitle(R.string.garmin_watch_send_key)
                            .setMessage(R.string.garmin_watch_send_key_confirm)
                            .setPositiveButton(R.string.garmin_watch_send, (dialog, which) ->
                                    // The outcome on screen: a silent failure looked like success.
                                    GarminBridge.sendKey(preference.getContext(), line -> {
                                        if (!line.startsWith("Skipping") && !line.startsWith("Initializing")
                                                && !line.startsWith("SDK ready")) {
                                            JoH.static_toast_long(line);
                                        }
                                    }))
                            .setNegativeButton(R.string.cancel, null)
                            .show();
                    return true;
                });
            }
        } catch (Exception e) {
            UserError.Log.e(TAG, "attach: " + e);
            JoH.static_toast_long("Garmin watch settings: " + e.getMessage());
        }
    }
}
