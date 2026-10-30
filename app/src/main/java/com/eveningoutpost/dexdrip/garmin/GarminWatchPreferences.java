package com.eveningoutpost.dexdrip.garmin;

import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.Intent;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.SwitchPreference;
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
     * Collector mode (owner, 2026-10-30): one setting for who connects to the G7 and on which
     * slot, so no two settings can disagree. "none": nobody collects; "phone": this phone on the
     * app slot 02; "watch02": the watch on the app slot 02; "watch03": the watch on its own slot
     * 03; "dual": the watch on 03 and this phone on 02. Only one client may use a slot. The
     * watch's menu can change it too; the newer change wins. Replaces the collector switch, the
     * slot list and the dual switch (garmin_watch_collector, _slot, _dual_collector), which are
     * converted once.
     */
    public static final String PREF_MODE = "garmin_watch_collector_mode";
    private static final String LEGACY_COLLECTOR = "garmin_watch_collector";
    private static final String LEGACY_SLOT = "garmin_watch_slot";
    private static final String LEGACY_DUAL = "garmin_watch_dual_collector";
    /** While the watch collects alone: switch back to this phone after missed readings. */
    public static final String PREF_FALLBACK = "garmin_watch_fallback";
    public static final String PREF_FALLBACK_MINUTES = "garmin_watch_fallback_minutes";
    /** When the collector mode last changed (epoch seconds), here or on the watch. */
    private static final String STORE_COLLECTOR_CHANGED = "garmin_watch_collector_changed";
    private static final int NOTIFICATION_ID = 7713;
    /** Watch apps that have messaged this phone (GarminWatchMessages.WATCH_APP_IDS, comma-separated). */
    private static final String STORE_HEARD_APPS = "garmin_watch_heard_apps";
    /** Action: send this phone's saved G7 key to the watch. */
    public static final String PREF_SEND_KEY = "garmin_watch_send_key";

    public static boolean isEnabled() {
        return Pref.getBooleanDefaultFalse(PREF_ENABLED);
    }

    /** The watch apps to message: those heard from, else G7 Scout. */
    public static java.util.List<String> watchAppTargets() {
        return GarminWatchMessages.sendTargets(PersistentStore.getString(STORE_HEARD_APPS));
    }

    /** Remembers a watch app that messaged this phone; true if it was new. */
    public static boolean noteWatchApp(String appId) {
        final String before = PersistentStore.getString(STORE_HEARD_APPS);
        final String after = GarminWatchMessages.addHeardApp(before, appId);
        if (after.equals(before)) return false;
        PersistentStore.setString(STORE_HEARD_APPS, after);
        return true;
    }

    /** The collector mode; settings from before 2026-10-30 are converted the first time. */
    public static String collectorMode() {
        final String mode = Pref.getString(PREF_MODE, "");
        if (GarminWatchMessages.isCollectorMode(mode)) return mode;
        final String migrated = legacyMode();
        Pref.setString(PREF_MODE, migrated);
        return migrated;
    }

    private static String legacyMode() {
        final String slot = Pref.getString(LEGACY_SLOT, "auto");
        if (Pref.getBooleanDefaultFalse(LEGACY_DUAL) && "03".equals(slot)) return "dual";
        if (Pref.getBooleanDefaultFalse(LEGACY_COLLECTOR)) return "02".equals(slot) ? "watch02" : "watch03";
        return "phone";
    }

    /** The watch collects (alone, or with this phone in dual mode). */
    public static boolean isWatchCollector() {
        if (!isEnabled()) return false;
        final String mode = collectorMode();
        return "watch02".equals(mode) || "watch03".equals(mode) || "dual".equals(mode);
    }

    /** This phone's G7 collector stays off: nobody, or only the watch, collects. */
    public static boolean isPhoneCollectorOff() {
        if (!isEnabled()) return false;
        final String mode = collectorMode();
        return "none".equals(mode) || "watch02".equals(mode) || "watch03".equals(mode);
    }

    /** Watch readings are stored while only the watch collects (in dual mode the phone has its own). */
    public static boolean useWatchGlucose() {
        if (!isEnabled()) return false;
        final String mode = collectorMode();
        return "watch02".equals(mode) || "watch03".equals(mode);
    }

    public static long collectorChangedAtSeconds() {
        return PersistentStore.getLong(STORE_COLLECTOR_CHANGED);
    }

    static String label(String mode) {
        switch (mode) {
            case "none": return "nobody collects";
            case "watch02": return "the Garmin watch, app slot 02";
            case "watch03": return "the Garmin watch, watch slot 03";
            case "dual": return "the Garmin watch on 03 and this phone on 02";
            default: return "this phone, app slot 02";
        }
    }

    /** Applies a mode chosen on the watch or by the fallback, then starts or stops the phone collector. */
    static void setCollectorMode(String mode, long changedAtSeconds, String why) {
        Pref.setString(PREF_MODE, mode);
        PersistentStore.setLong(STORE_COLLECTOR_CHANGED, changedAtSeconds);
        UserError.Log.uel(TAG, "G7 collector: " + label(mode) + " (" + why + ")");
        CollectionServiceStarter.restartCollectionServiceBackground();
    }

    static void notifyCollector(String mode, String why) {
        try {
            final PendingIntent home = PendingIntent.getActivity(xdrip.getAppContext(), 0,
                    new Intent(xdrip.getAppContext(), Home.class),
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            JoH.showNotification("G7 collector changed", "G7 collector: " + label(mode) + " (" + why + ")",
                    home, NOTIFICATION_ID, false, false, true);
        } catch (Exception e) {
            JoH.static_toast_long("G7 collector: " + label(mode));
        }
    }

    /** MissedReadingService: like "Disable Force Wear after missed readings". */
    public static void checkFallback() {
        if (!useWatchGlucose() || !Pref.getBooleanDefaultFalse(PREF_FALLBACK)) return;
        final int minutes = Math.max(10, Pref.getStringToInt(PREF_FALLBACK_MINUTES, 30));
        if (BgReading.getTimeSinceLastReading() < minutes * Constants.MINUTE_IN_MS) return;
        final String why = "no reading for " + minutes + " min";
        setCollectorMode("phone", JoH.tsl() / 1000, why);
        notifyCollector("phone", why);
        GarminBridge.sendCollector(xdrip.getAppContext(), null);
    }

    /** The collector mode changed here: after it is saved, apply it and tell the watch. */
    private static void collectorChanged() {
        PersistentStore.setLong(STORE_COLLECTOR_CHANGED, JoH.tsl() / 1000);
        Inevitable.task("garmin-collector-changed", 300, () -> {
            CollectionServiceStarter.restartCollectionServiceBackground();
            GarminBridge.sendCollector(xdrip.getAppContext(), line -> {
                if (line.startsWith("Sent") || line.contains("failed") || line.startsWith("No ")) {
                    JoH.static_toast_long(line);
                }
            });
        });
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
            final Preference mode = fragment.findPreference(PREF_MODE);
            if (mode != null) {
                // Shows a mode converted from the old switches.
                if (mode instanceof ListPreference) ((ListPreference) mode).setValue(collectorMode());
                mode.setOnPreferenceChangeListener((preference, newValue) -> {
                    collectorChanged();
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
