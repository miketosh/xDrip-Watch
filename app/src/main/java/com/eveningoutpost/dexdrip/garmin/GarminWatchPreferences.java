package com.eveningoutpost.dexdrip.garmin;

import android.app.AlertDialog;
import android.preference.Preference;
import android.preference.PreferenceFragment;

import com.eveningoutpost.dexdrip.R;
import com.eveningoutpost.dexdrip.models.JoH;
import com.eveningoutpost.dexdrip.models.UserError;
import com.eveningoutpost.dexdrip.utilitymodels.Pref;

/**
 * Settings → Smart Watch Features → Garmin watch (G7 Scout). Everything here is off by default;
 * the screen is in pref_advanced_settings.xml and attached with one line in Preferences.
 */
public final class GarminWatchPreferences {
    private GarminWatchPreferences() {}

    private static final String TAG = "GarminWatch";

    /** Talk to the G7 Scout Connect IQ app through Garmin Connect. */
    public static final String PREF_ENABLED = "garmin_watch_enabled";
    /** Store glucose the watch reads directly from the G7. */
    public static final String PREF_USE_GLUCOSE = "garmin_watch_use_glucose";
    /** Action: send this phone's saved G7 key to the watch. */
    public static final String PREF_SEND_KEY = "garmin_watch_send_key";

    public static boolean isEnabled() {
        return Pref.getBooleanDefaultFalse(PREF_ENABLED);
    }

    public static boolean useWatchGlucose() {
        return isEnabled() && Pref.getBooleanDefaultFalse(PREF_USE_GLUCOSE);
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
